FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY . .
RUN ./gradlew shadowJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/build/libs/Scholarfind-1.0-all.jar /app/Scholarfind-1.0-all.jar
ENTRYPOINT ["java", "-jar", "/app/myapp.jar"]
CMD []