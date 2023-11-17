FROM eclipse-temurin:17-jre
WORKDIR /surfer/
COPY build/libs/*-SNAPSHOT-all.jar ./surfer.jar

ENTRYPOINT ["java","-jar","/surfer/surfer.jar"]
