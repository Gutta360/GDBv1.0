FROM eclipse-temurin:21-jre
WORKDIR /opt/gdb
COPY target/gdb-backend-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8095
ENTRYPOINT ["java", "-jar", "/opt/gdb/app.jar"]
