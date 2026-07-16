# Slim runtime image (vs the generated src/main/docker/Dockerfile.jvm whose
# ubi9/openjdk-21-runtime base alone is ~630MB). Requires `./mvnw package` first.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /deployments
# lib/ first: biggest layer, changes least -> best build-cache hit rate
COPY target/quarkus-app/lib/ lib/
COPY target/quarkus-app/*.jar ./
COPY target/quarkus-app/app/ app/
COPY target/quarkus-app/quarkus/ quarkus/

ENV JAVA_OPTS="-Dquarkus.http.host=0.0.0.0"
EXPOSE 8080
USER nobody
CMD ["sh", "-c", "java $JAVA_OPTS -jar quarkus-run.jar"]
