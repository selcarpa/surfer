FROM eclipse-temurin:21-jre
WORKDIR /surfer/
COPY build/libs/*-SNAPSHOT-all.jar ./surfer.jar

ENTRYPOINT ["java","-jar","surfer.jar"]
