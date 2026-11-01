FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
RUN addgroup -S afi && adduser -S afi -G afi
WORKDIR /app
RUN mkdir -p /var/lib/afi/documents && chown -R afi:afi /var/lib/afi
COPY --from=build /build/target/resolve-0.1.0.jar app.jar
USER afi
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
