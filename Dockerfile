FROM zenika/alpine-maven:3-jdk8

RUN apk add --no-cache tini
ENTRYPOINT ["/sbin/tini", "--"]

RUN mkdir -p /usr/src/app/data

ADD pom.xml /usr/src/app/pom.xml
WORKDIR /usr/src/app
RUN ["mvn", "dependency:resolve"]
RUN ["mvn", "verify"]

ADD fileServerCoAP.properties /usr/src/app/fileServerCoAP.properties
ADD src /usr/src/app/src
RUN ["mvn", "package"]

CMD ["java", "-jar", "target/iotagent-leshan-1.1.0-jar-with-dependencies.jar"]
