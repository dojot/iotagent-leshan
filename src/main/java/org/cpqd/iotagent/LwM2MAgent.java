package org.cpqd.iotagent;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Vector;

import org.apache.log4j.Logger;
import org.cpqd.iotagent.DeviceMapper.DeviceControlStructure;
import org.cpqd.iotagent.lwm2m.objects.DevicePath;
import org.cpqd.iotagent.lwm2m.objects.FirmwareUpdatePath;
import org.cpqd.iotagent.lwm2m.objects.SecurityPath;
import org.cpqd.iotagent.lwm2m.utils.LwM2MEvent;
import org.cpqd.iotagent.lwm2m.utils.ResourceBlackListMgmt;
import org.eclipse.leshan.core.node.LwM2mMultipleResource;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.node.LwM2mSingleResource;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeEncoder;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.response.ObserveResponse;
import org.eclipse.leshan.server.californium.LeshanServerBuilder;
import org.eclipse.leshan.server.californium.impl.LeshanServer;
import org.eclipse.leshan.server.impl.InMemorySecurityStore;
import org.eclipse.leshan.server.observation.ObservationListener;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.registration.RegistrationListener;
import org.eclipse.leshan.server.registration.RegistrationUpdate;
import org.eclipse.leshan.server.security.NonUniqueSecurityInfoException;
import org.eclipse.leshan.server.security.SecurityInfo;
import org.eclipse.leshan.util.Hex;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import br.com.dojot.IoTAgent.IoTAgent;
import br.com.dojot.utils.Services;


public class LwM2MAgent implements Runnable {
    private Logger logger = Logger.getLogger(LwM2MAgent.class);
    private ImageDownloader imageDownloader;
    private DeviceMapper deviceMapper;
    private LwM2mHandler requestHandler;
    private LeshanServer server;
    private IoTAgent eventHandler;
    private InMemorySecurityStore securityStore;
    private FileServerPskStore fsPskStore;
    private StatusPublisher statusPublisher;


    public LwM2MAgent(long consumerPollTime, ImageDownloader imageDownloader, FileServerPskStore pskStore) {
        try {
            this.eventHandler = new IoTAgent(consumerPollTime);
        } catch (Exception ex) {
            logger.error("IoT-Agent initialization failed. Bailing out! (" + ex + ")");
            System.exit(1);
        }
        this.deviceMapper = new DeviceMapper();
        this.securityStore = new InMemorySecurityStore();
        this.imageDownloader = imageDownloader;
        this.fsPskStore = pskStore;
        this.statusPublisher = new StatusPublisher(eventHandler);

        // register the callbacks to treat the events
        this.eventHandler.on("iotagent.device", "device.create", this::on_create);
        this.eventHandler.on("iotagent.device", "device.update", this::on_create);
        this.eventHandler.on("iotagent.device", "device.remove", this::on_remove);
        this.eventHandler.on("iotagent.device", "device.configure", this::on_actuate);
    }

    /**
     * @brief The bootstrap procedure retrieves information about devices previously created from to device manager to
     * build a updated vision about the devices
     */
    public boolean bootstrap() {
        this.eventHandler.generateDeviceCreateEventForActiveDevices();
        return true;
    }

    public void setKeyPskStore(String keyId, String psk) {
        this.fsPskStore.setKey(keyId, psk.getBytes());
    }

    /**
     * This method is part of Firmware Update. The first thing to do in a firmware update in LwM2m protocol
     * is send the Package URI to the device. The next step, is wait until the device send that his state
     * has changed to downloaded (state 2), and, then, actuate on the attribute "FWUpdate-Update", that
     * will trigger the Firmware Update on the device.
     *
     * @param registration, newFwVersion, tenant
     * @return
     */
	private void sendsUriToDevice(Registration registration, String imageLabel, String newFwVersion, String tenant,
			boolean isDeviceSecure, String imageId, Map<String, String> queryParams) {

        logger.debug("Will try to send URI to device");

        //Verification if the fw version is really changing.
        LwM2mSingleResource currentFwVersionResource = requestHandler.ReadResource(registration, DevicePath.FIRMWARE_VERSION);
        if (currentFwVersionResource == null) {
            logger.error("Failed to read current firmware version");
            return;
        }
        String currentFwVersion = (String) currentFwVersionResource.getValue();
        logger.debug("Current FW version: " + currentFwVersion);
        logger.debug("Desirable FW version: " + newFwVersion);

        //empty string is written to the Package URI
        //restart to idle
        if (newFwVersion == null || newFwVersion.trim().isEmpty()) {
            logger.debug("Will write Empty in resource package URI and discard transfer");
            requestHandler.WriteResource(registration, FirmwareUpdatePath.PACKAGE_URI, "");
            return;
        }
        //Gets URL to give it to device if the version is actual changing
        if (!currentFwVersion.equals(newFwVersion)) {
            logger.debug("Versions have actual changed");

            // Verify the supported protocol
            LwM2mMultipleResource supportedProtocolResource = requestHandler.ReadMultipleResource(registration, FirmwareUpdatePath.FIRMWARE_UPDATE_PROTOCOL_SUPPORT);
            if (supportedProtocolResource == null) {
                logger.error("Failed to read the supported protocol to execute the firmware update");
                return;
            }
            Map<Integer, ?> protocolMap = supportedProtocolResource.getValues();
            Vector<Integer> supportedProtocols = new Vector<Integer>();
            for (Map.Entry<Integer, ?> entry : protocolMap.entrySet()) {
                supportedProtocols.add((Integer)((Long)entry.getValue()).intValue());
            }

            int supportedProtocol = 0;
            try {                
               supportedProtocol = selectFirmwareUpdateProtocol(supportedProtocols, isDeviceSecure);
            } catch (Exception e) {
                logger.warn(e.toString());
                return;
            }

            String fileUri = null;
            try {
				fileUri = imageDownloader.downloadImageAndGenerateUri(tenant, imageLabel, newFwVersion,
						supportedProtocol, imageId, queryParams);
            } catch (Exception e) {
                logger.error(e.getMessage());
                return;
            }
            logger.debug("Got the file URI: " + fileUri);
            logger.debug("Will write URI in resource package URI");
            requestHandler.WriteResource(registration, FirmwareUpdatePath.PACKAGE_URI, fileUri);
        } else {
            logger.debug("Device already up-to-date");
        }
        return;
    }

    private int selectFirmwareUpdateProtocol(Vector<Integer> supportedProtocols, boolean isDeviceSecure) {
        boolean isCoapSupported = false;
        boolean isCoapsSupported = false;
        boolean isHttpSupported = false;
        boolean isHttpsSupported = false;
        
        for (int i = 0; i < supportedProtocols.size(); ++i) {
            switch(supportedProtocols.get(i)) {
                case FirmwareUpdatePath.PROTOCOL_COAP:
                    isCoapSupported = true;
                    break;
                case FirmwareUpdatePath.PROTOCOL_COAPS:
                    isCoapsSupported = true;
                    break;
                case FirmwareUpdatePath.PROTOCOL_HTTP:
                    isHttpSupported = true;
                    break;
                case FirmwareUpdatePath.PROTOCOL_HTTPS:
                    isHttpsSupported = true;
                    break;
                default:
                    break;
            }
        }

        if ((isDeviceSecure) && (isHttpsSupported)) {
            return FirmwareUpdatePath.PROTOCOL_HTTPS;
        }
        if ((isDeviceSecure) && (isCoapsSupported)) {
            return FirmwareUpdatePath.PROTOCOL_COAPS;
        }
        if (isHttpSupported) {
            return FirmwareUpdatePath.PROTOCOL_HTTP;
        }
        if (isCoapSupported) {
            return FirmwareUpdatePath.PROTOCOL_COAP;
        }

        throw new RuntimeException("Cannot define a protocol");
    }

    private JSONObject transformLwm2mResourceValueIntoJson(DeviceAttribute attr, LwM2mResource resource) throws Exception {
        JSONObject attrJson = new JSONObject();
        String valueType = attr.getValueType();

        if (resource.isMultiInstances()) {
            String resourceData = String.format("LwM2mMultipleResource [values=%s, type=%s]",
                resource.getValues().toString(), resource.getType().toString());
            attrJson.put(attr.getLabel(), resourceData);
            return attrJson;
        }

        switch (resource.getType()) {
            case BOOLEAN:
                attrJson.put(attr.getLabel(), (Boolean) resource.getValue());
                break;
            case FLOAT:
                attrJson.put(attr.getLabel(), (Double) resource.getValue());
                break;
            case INTEGER:
                attrJson.put(attr.getLabel(), (Long) resource.getValue());
                break;
            case STRING:
                attrJson.put(attr.getLabel(), (String) resource.getValue());
                break;
            case TIME:
                attrJson.put(attr.getLabel(), ((Date) resource.getValue()).toString());
                break;
            case OPAQUE:
                byte[] data = (byte[]) resource.getValue();
                transformLwm2mResourceValueIntoJsonOpaqueCases(attr, attrJson, valueType, data);
                break;
            default:
                logger.error("Unsupported resource type: " + resource.getType().toString());
                throw new Exception();
        }

        return attrJson;
    }

    private void transformLwm2mResourceValueIntoJsonOpaqueCases(DeviceAttribute attr, JSONObject attrJson, String valueType, byte[] data) throws Exception {
        if (valueType.equals("interger")) {
            switch (data.length) {
                case 1:
                    Byte b = data[0];
                    attrJson.put(attr.getLabel(), b.intValue());
                    break;
                case 2:
                    attrJson.put(attr.getLabel(), ByteBuffer.wrap(data).getShort());
                    break;
                case 4:
                    attrJson.put(attr.getLabel(), ByteBuffer.wrap(data).getInt());
                    break;
                case 8:
                    attrJson.put(attr.getLabel(), ByteBuffer.wrap(data).getLong());
                    break;
                default:
                    logger.error("Attribute " + attr.getLwm2mPath() +
                            " mapped as integer but received " +
                            data.length + " bytes.");
                    throw new Exception();
            }
        } else if (valueType.equals("boolean")) {
            if (data.length != 1) {
                logger.error("Attribute " + attr.getLwm2mPath() +
                        " mapped as boolean but received " +
                        data.length + " bytes.");
                throw new Exception();
            }
            if (data[0] == 1) {
                attrJson.put(attr.getLabel(), true);
            } else {
                attrJson.put(attr.getLabel(), false);
            }
        } else if (valueType.equals("float")) {
            switch (data.length) {
                case 4:
                    attrJson.put(attr.getLabel(), ByteBuffer.wrap(data).getFloat());
                    break;
                case 8:
                    attrJson.put(attr.getLabel(), ByteBuffer.wrap(data).getDouble());
                    break;
                default:
                    logger.error("Attribute " + attr.getLwm2mPath() +
                            " mapped as float but received " +
                            data.length + " bytes.");
                    throw new Exception();
            }
        } else { // we are assuming the others type are string compatible (is it safe?)
            attrJson.put(attr.getLabel(), new String(data));
        }
    }

    /**
     * @param message
     * @return
     * @brief This method is a callback and it is called every time a new device is created. It creates a device
     * representation, register the security key, if any, and can trigger the observation procedure if applicable
     */
    private Void on_create(String tenant, String message) {
        JSONObject messageObj = new JSONObject(message);
        logger.debug("on_create: " + messageObj.toString());

        // try to build a device representation
        Device device;
        try {
            device = new Device(messageObj.getJSONObject("data"));
        } catch (JSONException e) {
            logger.error("Invalid json");
            return null;
        } catch (Exception e) {
            // this it not a lwm2m device, just skip it
            return null;
        }

        String deviceId = device.getDeviceId();
        String clientEndpoint = device.getClientEndpoint();

        Services iotAgent = Services.getInstance();
        iotAgent.addDeviceToCache(tenant, deviceId, messageObj.getJSONObject("data"));

        if (device.isSecure()) {
            // '/0/0/5' is the standard path to pre-shared key value
            DeviceAttribute pskAttr = device.getAttributeByPath(SecurityPath.PRE_SHARED_SECRET_KEY);
            String psk = (String) pskAttr.getStaticValue();
            // '/0/0/3' is the standard path to the pre-shared key identity
            DeviceAttribute pskIdentityAttr = device.getAttributeByPath(SecurityPath.PRE_SHARED_KEY_IDENTITY);
            String pskIdentity = (String) pskIdentityAttr.getStaticValue();
            SecurityInfo securityInfo = SecurityInfo.newPreSharedKeyInfo(clientEndpoint,
                    pskIdentity,
                    Hex.decodeHex(psk.toCharArray()));
            try {
                this.securityStore.remove(clientEndpoint);
                this.securityStore.add(securityInfo);
                logger.info("Inserting pskId ioto psk store");
                this.fsPskStore.setKey(pskIdentity, psk.getBytes());
                logger.debug("Adding a psk to device: " + deviceId);
            } catch (NonUniqueSecurityInfoException e) {
                e.printStackTrace();
                return null;
            }
        } else {
            logger.debug("device: " + deviceId + " is not using DTLS");
        }

        DeviceControlStructure controlStructure = this.deviceMapper.addNorthboundAssociation(clientEndpoint,
                deviceId,
                tenant);
        if (controlStructure.isSouthboundAssociate()) {
            logger.debug("Observing some attributes");
            this.requestHandler.CancelAllObservations(controlStructure.registration);
            observeResources(deviceId, tenant, device.getReadableAttributes(), controlStructure.registration);
        } else {
            logger.debug("skipping observing, southbound is not registered yet");
        }

        return null;
    }

    private void observeResources(String deviceId, String tenant,
                                  LinkedList<DeviceAttribute> readableAttrs, Registration registration) {

        JSONObject attrJson;
        JSONObject allAttrsJson = new JSONObject();
        for (DeviceAttribute attr : readableAttrs) {
            String path = attr.getLwm2mPath();
            logger.debug("Observing: " + attr.getLabel());
            try {
                LwM2mResource resource = requestHandler.ObserveResource(registration, path);
                if (resource == null) {
                    throw new Exception();
                }
                attrJson = transformLwm2mResourceValueIntoJson(attr, resource);
                
                Object receivedValue = attrJson.get(attr.getLabel());
                if (!ResourceBlackListMgmt.getInstance().isBlackListed(path, String.valueOf(receivedValue))) {
                    allAttrsJson.put(attr.getLabel(), receivedValue);
                } else {
                    logger.info("The value " + receivedValue + " of resource " + path + " was discarted");
                }
                
            } catch (Exception e) {
                this.logger.warn("Failed to observe resource: " + attr.getLwm2mPath());
            }
        }
        eventHandler.updateAttrs(deviceId, tenant, allAttrsJson, null);
    }

    private Void on_remove(String tenant, String message) {
        JSONObject messageObj = new JSONObject(message);
        logger.debug("on_remove: " + messageObj.toString());

        try {
            String deviceId = null;
            deviceId = messageObj.getJSONObject("data").getString("id");

            Services iotAgent = Services.getInstance();
            iotAgent.removeDeviceFromCache(tenant, deviceId);
        } catch (Exception e) {
            logger.error("Failed to clear cache, agent can have misbehavior");
        }

        Device device;
        try {
            device = new Device(messageObj.getJSONObject("data"));
        } catch (JSONException e) {
            logger.error("Invalid json");
            return null;
        } catch (Exception e) {
            // this it not a lwm2m device, just skip it
            return null;
        }

        if (device.isSecure()) {
            DeviceAttribute pskIdentityAttr = device.getAttributeByPath(SecurityPath.PRE_SHARED_KEY_IDENTITY);
            String pskIdentity = (String) pskIdentityAttr.getStaticValue();
            logger.info("removing pskId from psk store");
            this.fsPskStore.removeKey(pskIdentity);
        }

        String clientEndpoint = device.getClientEndpoint();
        DeviceControlStructure controlStructure = this.deviceMapper.getDeviceControlStructure(clientEndpoint);
        if (controlStructure.isSouthboundAssociate()) {
            this.requestHandler.CancelAllObservations(controlStructure.registration);
        }
        this.deviceMapper.removeNorthboundAssociation(clientEndpoint);
        return null;
    }

    private Void on_actuate(String tenant, String message) {
        JSONObject messageObj = new JSONObject(message);
        logger.debug("on_actuate: " + messageObj.toString());

        String deviceId = messageObj.getJSONObject("data").getString("id");

        DeviceAttribute devAttr;

        Services iotAgent = Services.getInstance();
        JSONObject deviceJson = iotAgent.getDevice(deviceId, tenant);
        Device device;
        try {
            device = new Device(deviceJson);
        } catch (Exception e) {
            return null;
        }

        DeviceControlStructure controlStruture = this.deviceMapper.getDeviceControlStructure(device.getClientEndpoint());
        if ((controlStruture == null) || (!controlStruture.isSouthboundAssociate())) {
            logger.error("Device: " + device.getDeviceId() + " is not registered");
            return null;
        }

        JSONObject attrs = messageObj.getJSONObject("data").getJSONObject("attrs");
        JSONArray targetAttrs = attrs.names();

        for (int i = 0; i < targetAttrs.length(); ++i) {
            String targetAttr = targetAttrs.getString(i);
            devAttr = device.getAttributeByLabel(targetAttr);
            if (devAttr != null) {
                logger.debug("actuating on attribute: " + devAttr.getLabel());
                String path = devAttr.getLwm2mPath();

                // check if it is a firmware update request
                if (path.equals(FirmwareUpdatePath.PACKAGE_URI)) {

                    // Verify if the device supports this delivery method
                    LwM2mSingleResource deliveryMethodResource = requestHandler.ReadResource(controlStruture.registration, FirmwareUpdatePath.FIRMWARE_UPDATE_DELIVERY_METHOD);
                    if (deliveryMethodResource == null) {
                        logger.error("Failed to read the supported delivert method to transfer firmware image");
                        continue;
                    }
                    int deliveryMethod = (int)(long) deliveryMethodResource.getValue();
                    if (deliveryMethod == FirmwareUpdatePath.DELIVERY_METHOD_PUSH) {
                        logger.error("This device only supports delivery method push. The URI will not be send to the device.");
                        continue;
                    }

                    String imageVersion = attrs.getString(targetAttr);
                    String imageLabel = devAttr.getTemplateId();
                    logger.info("Image id that came on actuation: " + imageVersion);
                    this.sendsUriToDevice(controlStruture.registration, imageLabel, imageVersion, tenant, device.isSecure(), null, null);
                    continue;
                }

                if (devAttr.isExecutable()) {
                    logger.debug("excuting");
                    requestHandler.ExecuteResource(controlStruture.registration, path, attrs.getString(targetAttr));
                } else if (devAttr.isWritable()) {
                    logger.debug("writing");
                    requestHandler.WriteResource(controlStruture.registration, path, attrs.get(targetAttr));
                }
            } else {
                logger.warn("skipping attribute: " + targetAttr + ". Not found.");
            }
        }

        return null;
    }

    private final RegistrationListener registrationListener = new RegistrationListener() {

        @Override
        public void registered(Registration registration,
                               Registration previousReg,
                               Collection<Observation> previousObsersations) {
            logger.debug("registered: " + registration.toString());

            DeviceControlStructure controlStructure = deviceMapper.addSouthboundAssociation(registration.getEndpoint(),
                    registration);
            if (controlStructure.isNorthboundAssociate()) {
                logger.debug("Observing some attributes");

                Services iotAgent = Services.getInstance();
                JSONObject deviceJson = iotAgent.getDevice(controlStructure.deviceId, controlStructure.tenant);
                if (deviceJson == null) {
                    logger.warn("Device " + controlStructure.deviceId + " has not found");
                    return;
                }
                Device device;
                try {
                    device = new Device(deviceJson);
                } catch (Exception e) {
                    logger.error("Unexpected situation: " + e.toString());
                    return;
                }

                requestHandler.CancelAllObservations(controlStructure.registration);
                observeResources(controlStructure.deviceId, controlStructure.tenant,
                        device.getReadableAttributes(), controlStructure.registration);
                
                Map<String, String> automaticFirmwareUpdateInfo = new AutomaticFirmwareUpdate(deviceJson).download();
                if (automaticFirmwareUpdateInfo != null) {
                    Map<String, String> queryParams = new LinkedHashMap<>();
                    queryParams.put("m", automaticFirmwareUpdateInfo.get(AutomaticFirmwareUpdate.MANDATORY));
                    queryParams.put("d", automaticFirmwareUpdateInfo.get(AutomaticFirmwareUpdate.NOTES));

                    sendsUriToDevice(registration, null,
                            automaticFirmwareUpdateInfo.get(AutomaticFirmwareUpdate.DESIRED_FIRMWARE),
                            controlStructure.tenant, device.isSecure(),
                            automaticFirmwareUpdateInfo.get(AutomaticFirmwareUpdate.IMAGE_ID), queryParams);
                }
				
				statusPublisher.publish(controlStructure.deviceId, controlStructure.tenant, LwM2MEvent.REGISTER);
				
            } else {
                logger.debug("skpping observing, northbound is not registered yet");
            }

        }

        @Override
        public void updated(RegistrationUpdate update, Registration updatedReg, Registration previousReg) {
            logger.debug("updated: " + update.toString());

            deviceMapper.addSouthboundAssociation(updatedReg.getEndpoint(), updatedReg);

        }

        @Override
        public void unregistered(Registration registration,
                                 Collection<Observation> observations,
                                 boolean expired,
                                 Registration newReg) {
            logger.debug("device left: " + registration.getEndpoint());

            deviceMapper.removeSouthboundAssociation(registration.getEndpoint());
            
            DeviceControlStructure deviceControlStructure = deviceMapper
                    .getDeviceControlStructure(registration.getEndpoint());
            statusPublisher.publish(deviceControlStructure.deviceId, deviceControlStructure.tenant,
                    LwM2MEvent.UNREGISTER);
        }
    };

    private final ObservationListener observationListener = new ObservationListener() {
        @Override
        public void cancelled(Observation observation) {
        }

        @Override
        public void onResponse(Observation observation, Registration registration, ObserveResponse response) {
            logger.debug("Received notification from [" + observation.getPath() + "]");

            //retrieve response content
            LwM2mNode lwm2mNode = response.getContent();
            if (lwm2mNode == null) {
                logger.warn("Response is null. Skipping it");
                return;
            }
            if (!(lwm2mNode instanceof LwM2mResource)) {
                logger.warn("Unsuported content object.");
                return;
            }
            LwM2mResource resource = (LwM2mResource) lwm2mNode;

            //retrieve device's attribute information
            DeviceControlStructure controlStructure = deviceMapper.getDeviceControlStructure(registration.getEndpoint());
            if (controlStructure == null) {
                logger.warn("Unknown endpoint: " + registration.getEndpoint());
                return;
            }
            if (!controlStructure.isNorthboundAssociate()) {
                logger.warn("There is not device associate yet with the endpoint: " + registration.getEndpoint());
                return;
            }
            Services iotAgent = Services.getInstance();
            JSONObject deviceJson = iotAgent.getDevice(controlStructure.deviceId, controlStructure.tenant);
            if (deviceJson == null) {
                logger.warn("Device " + controlStructure.deviceId + " has not found");
                return;
            }

            Device device;
            try {
                device = new Device(deviceJson);
            } catch (Exception e) {
                logger.error("Unexpected situation");
                return;
            }
            DeviceAttribute attr = device.getAttributeByPath(observation.getPath().toString());
            if (attr == null) {
                logger.warn("Attribute with path " + observation.getPath().toString() + " is not mapped");
                return;
            }

            JSONObject attrJson;
            try {
                attrJson = transformLwm2mResourceValueIntoJson(attr, resource);
            } catch (Exception e) {
                return;
            }

            eventHandler.updateAttrs(controlStructure.deviceId,
                controlStructure.tenant, attrJson, null);
            
			
			if (new AutomaticFirmwareUpdate(deviceJson).applyImage(attrJson)) {
				requestHandler.ExecuteResource(registration, FirmwareUpdatePath.UPDATE, "1");
			}
			
        }

        @Override
        public void onError(Observation observation, Registration registration, Exception error) {
            logger.error("Unable to handle notification of" + observation.getRegistrationId().toString() + ": " + observation.getPath());
        }

        @Override
        public void newObservation(Observation observation, Registration registration) {
        }
    };


    @Override
    public void run() {
        try {
            LeshanServerBuilder builder = new LeshanServerBuilder();

            // Set encoder/decoders
            builder.setEncoder(new DefaultLwM2mNodeEncoder());
            builder.setDecoder(new DefaultLwM2mNodeDecoder());

            builder.setSecurityStore(this.securityStore);

            // Start Server
            server = builder.build();
            server.start();

            // Add Registration Treatment
            server.getRegistrationService().addListener(registrationListener);
            server.getObservationService().addListener(observationListener);

            // Initialize Request Handler
            requestHandler = new LwM2mHandler(server);

        } catch (Exception e) {
            e.printStackTrace();
            logger.error(e);
        }
    }

}
