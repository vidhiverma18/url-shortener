FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
# Patch the base image's own packages before installing anything. A published base image is
# only as current as its last rebuild, and the scan gate fails on fixable OS findings that the
# tag alone would carry indefinitely. The cost is that an image built today and one built next
# month are not byte-identical, which is the right way round for a service on the internet.
RUN apk upgrade --no-cache && apk add --no-cache wget \
    && addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/url-shortener-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
