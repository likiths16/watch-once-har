# Stage 1: build the fat jar. No browser/Playwright binaries needed anywhere in this image —
# replay is plain java.net.http.HttpClient, which is the whole point of the HAR-based design.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

# Stage 2: slim runtime image.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /build/target/watch-once-har.jar ./app.jar
RUN mkdir -p /app/data

ENV PORT=4000
ENV DB_PATH=/app/data/watch-once-har.db
ENV DEMO_PORT=8089

EXPOSE 4000
# No VOLUME directive here: Railway (and some other PaaS builders) reject Dockerfiles that
# declare one, wanting volumes attached through their own UI/config instead. DB_PATH defaults
# to a directory created at build time (/app/data) so the app runs immediately with no manual
# volume step; for durability across restarts/redeploys, attach a platform volume mounted at
# that same path (/app/data) later — no other change needed.

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
