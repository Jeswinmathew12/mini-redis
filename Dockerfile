# syntax=docker/dockerfile:1

# ---- Build stage: compile and package the jar --------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM alone first: the dependency download is then cached until the
# POM changes, instead of being repeated on every source edit.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in development / CI with `mvn test`; the image build only packages.
RUN mvn -B -q package -DskipTests

# ---- Runtime stage: JRE only, non-root ---------------------------------------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin miniredis
WORKDIR /app
COPY --from=build /build/target/mini-redis.jar /app/mini-redis.jar
USER miniredis

EXPOSE 6380

# Arguments are: port, max keys (0 = unlimited), bind address.
# The bind address MUST be 0.0.0.0 in a container; the app's default of
# 127.0.0.1 would make published ports unreachable. Override CMD to set a key
# limit, e.g.:  docker run -p 6380:6380 mini-redis 6380 100000 0.0.0.0
#
# Exec form, so the JVM is PID 1 and receives SIGTERM from `docker stop`
# directly; its shutdown hook then closes clients and stops the server cleanly.
# MaxRAMPercentage lets the heap use most of the container's memory limit (the
# JVM default is only 25%).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/mini-redis.jar"]
CMD ["6380", "0", "0.0.0.0"]

# Asks the server for DBSIZE over a plain TCP socket (no extra packages needed)
# and expects a number back. Assumes the default port 6380; if you change the
# port, change it here too or run with --no-healthcheck.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/6380 && printf "DBSIZE\r\n" >&3 && read -t 3 -r reply <&3 && [[ $reply =~ ^[0-9]+ ]]'
