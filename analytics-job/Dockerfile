# Build context: repository root. Compiles and tests on JDK 17; runs on Flink's Java 17 image.
FROM eclipse-temurin:17.0.20.1_1-jdk@sha256:91011320978c10ec51d2cde1eb6f616db6d2a739a0e521096c343dd3fed9abe6 AS build
WORKDIR /src
COPY mvnw ./
COPY .mvn .mvn
# No unzip in this image, so mvnw downloads the tar.gz: verify that artifact instead of the zip.
RUN sed -i 's/^distributionSha256Sum=.*/distributionSha256Sum=80ffca22aed9e8b9713a232f3394fd81d7f20322df75efdb2b047dbd3e3a23bb/' .mvn/wrapper/maven-wrapper.properties
COPY event-contracts event-contracts
COPY analytics-job analytics-job
ENV JAVA17_HOME=/opt/java/openjdk
RUN --mount=type=cache,target=/root/.m2 sh -c '\
    sh ./mvnw -B -ntp -f event-contracts/pom.xml -DskipTests install && \
    sh ./mvnw -B -ntp -f analytics-job/pom.xml verify'

FROM flink:2.2.1-java17@sha256:15e8901a97a3703ad351f8212166798e36dd411c100b869e30c7c5e6b64351b0
# Application mode loads the job from usrlib; a named volume inherits this directory's ownership.
COPY --from=build --chown=flink:flink /src/analytics-job/target/analytics-job.jar /opt/flink/usrlib/analytics-job.jar
RUN mkdir -p /opt/flink/checkpoints && chown flink:flink /opt/flink/checkpoints
# The JobManager's entry point: resumes from the newest completed checkpoint.
COPY --chmod=0755 analytics-job/docker/jobmanager.sh /opt/flink/bin/mse-jobmanager.sh
USER flink
