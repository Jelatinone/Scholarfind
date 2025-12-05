FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY . .
RUN chmod +x ./gradlew

RUN ./gradlew shadowJar

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /build/build/libs/Scholarfind-1.0-all.jar /app/scholarfind.jar

ENTRYPOINT ["java", "-jar", "/app/scholarfind.jar"]
CMD []
