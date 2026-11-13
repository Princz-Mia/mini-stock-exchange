# Build context: repository root.
FROM container-registry.oracle.com/java/jdk-no-fee-term:25.0.5@sha256:283d01db5d749b005826ad311af50a78cc40969548abb63d4b57063a6d611960 AS build
WORKDIR /src
COPY mvnw ./
COPY .mvn .mvn
# No unzip in this image, so mvnw downloads the tar.gz: verify that artifact instead of the zip.
RUN sed -i 's/^distributionSha256Sum=.*/distributionSha256Sum=80ffca22aed9e8b9713a232f3394fd81d7f20322df75efdb2b047dbd3e3a23bb/' .mvn/wrapper/maven-wrapper.properties
COPY event-contracts event-contracts
COPY services/execution-service services/execution-service
# The three Java images share this cache and build in parallel: locked, so one cannot read a Maven download
# another is still writing.
RUN --mount=type=cache,target=/root/.m2,sharing=locked sh -c '\
    sh ./mvnw -B -ntp -f event-contracts/pom.xml -DskipTests install && \
    sh ./mvnw -B -ntp -f services/execution-service/pom.xml -DskipTests package && \
    java -Djarmode=tools -jar services/execution-service/target/execution-service-0.1.0.jar extract --layers --destination /out'

FROM container-registry.oracle.com/java/jdk-no-fee-term:25.0.5@sha256:283d01db5d749b005826ad311af50a78cc40969548abb63d4b57063a6d611960
RUN groupadd --system --gid 10001 mse && useradd --system --uid 10001 --gid mse --no-create-home mse
# Owner-only, so a fresh named volume mounted here starts out owned by mse. The simulator runs as the same user.
RUN mkdir -p /run/mse/bot-tokens && chown mse:mse /run/mse/bot-tokens && chmod 700 /run/mse/bot-tokens
WORKDIR /app
COPY --from=build /out/dependencies/ ./
COPY --from=build /out/spring-boot-loader/ ./
COPY --from=build /out/snapshot-dependencies/ ./
COPY --from=build /out/application/ ./
USER mse
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=6 \
    CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "execution-service-0.1.0.jar"]
