# Multi-stage build for app-a
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy proto files
COPY proto/ /build/proto/

# Copy app-a source
COPY apps/app-a/pom.xml /build/apps/app-a/
COPY apps/app-a/src/ /build/apps/app-a/src/

# Build the application
WORKDIR /build/apps/app-a
RUN mvn clean package -DskipTests

# Runtime image
FROM eclipse-temurin:17-jre

WORKDIR /app

# Copy built JAR
COPY --from=builder /build/apps/app-a/target/*.jar /app/app.jar

# Expose HTTP port
EXPOSE 8080

# Set default environment variables
ENV B_SERVICE_URL=b-service:50051

# Run the application
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
