# syntax=docker/dockerfile:1
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Dockerfile for HugeGraph Server
ARG TARGETARCH

# Prepare the RISC-V Java runtime on the native build platform
FROM --platform=$BUILDPLATFORM maven:3.9.0-eclipse-temurin-11 AS riscv64-java

ARG TARGETARCH
ARG DRAGONWELL_RISCV64_URL="https://github.com/dragonwell-project/dragonwell11/releases/download/dragonwell-extended-11.0.31.28_jdk-11.0.31-ga/Alibaba_Dragonwell_Extended_11.0.31.28.11_riscv64_linux.tar.gz"
ARG DRAGONWELL_RISCV64_SHA256="7df2d308f0dca7a779d2854e6da19214f99cd77aa6d4982c3bf981a77266a79b"

RUN mkdir -p /opt/dragonwell \
    && if [ "$TARGETARCH" = "riscv64" ]; then \
         curl --fail --location --retry 3 --show-error \
              "$DRAGONWELL_RISCV64_URL" --output /tmp/dragonwell.tar.gz; \
         echo "$DRAGONWELL_RISCV64_SHA256  /tmp/dragonwell.tar.gz" | sha256sum -c -; \
         tar -xzf /tmp/dragonwell.tar.gz --strip-components=1 -C /opt/dragonwell; \
         rm /tmp/dragonwell.tar.gz; \
       fi

# Build source code
FROM --platform=$BUILDPLATFORM maven:3.9.0-eclipse-temurin-11 AS build

WORKDIR /pkg

COPY . .

ARG MAVEN_ARGS
ARG TARGETARCH

RUN --mount=type=cache,target=/root/.m2 \
    if [ "$TARGETARCH" = "riscv64" ]; then \
      mvn package $MAVEN_ARGS -e -B -ntp -Dmaven.test.skip=true \
          -Dmaven.javadoc.skip=true -Drocksdb-only \
          -pl hugegraph-server/hugegraph-dist -am \
      && hugegraph-server/hugegraph-dist/src/assembly/travis/check-rocksdb-only-dist.sh \
          hugegraph-server/apache-hugegraph-server-*/; \
    else \
      mvn package $MAVEN_ARGS -e -B -ntp -Dmaven.test.skip=true \
          -Dmaven.javadoc.skip=true \
      && rm ./hugegraph-server/*.tar.gz ./hugegraph-pd/*.tar.gz \
            ./hugegraph-store/*.tar.gz; \
    fi

# Keep the existing Temurin 11 runtime for every architecture it supports.
FROM --platform=linux/amd64 eclipse-temurin:11-jre-jammy AS runtime-amd64
FROM --platform=linux/arm/v7 eclipse-temurin:11-jre-jammy AS runtime-arm
FROM --platform=linux/arm64 eclipse-temurin:11-jre-jammy AS runtime-arm64
FROM --platform=linux/ppc64le eclipse-temurin:11-jre-jammy AS runtime-ppc64le
FROM --platform=linux/s390x eclipse-temurin:11-jre-jammy AS runtime-s390x

# Temurin 11 has no RISC-V image, so use Ubuntu with the verified Dragonwell archive.
FROM ubuntu:24.04 AS runtime-riscv64
COPY --from=riscv64-java /opt/dragonwell/ /opt/dragonwell/
ENV JAVA_HOME="/opt/dragonwell" \
    PATH="/opt/dragonwell/bin:${PATH}"

# Runtime environment
# Note: ZGC (The Z Garbage Collector) is only supported on ARM-Mac with java > 13
FROM runtime-${TARGETARCH} AS runtime

ARG TARGETARCH
ARG DEBIAN_FRONTEND=noninteractive

COPY --from=build /pkg/hugegraph-server/apache-hugegraph-server-*/ /hugegraph-server/
LABEL maintainer="HugeGraph Docker Maintainers <dev@hugegraph.apache.org>"

# TODO: use g1gc or zgc as default
# Note: --add-exports is required for Java 11+ to access jdk.internal.reflect for auth proxy
ENV JAVA_OPTS="-XX:+UnlockExperimentalVMOptions -XX:+UseContainerSupport -XX:MaxRAMPercentage=50 -XshowSettings:vm --add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED" \
    HUGEGRAPH_HOME="hugegraph-server" \
    STDOUT_MODE="true"

WORKDIR /hugegraph-server/

# 1. Install runtime dependencies and configure server
RUN apt-get -q update \
    && runtime_packages="dumb-init procps curl lsof vim" \
    && if [ "$TARGETARCH" = "riscv64" ]; then \
         runtime_packages="$runtime_packages ca-certificates libatomic1 libstdc++6 libgcc-s1"; \
       fi \
    && apt-get -q install -y --no-install-recommends --no-install-suggests $runtime_packages \
    && if [ "$TARGETARCH" = "riscv64" ]; then \
         test -x /opt/dragonwell/bin/java; \
       fi \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* \
    && sed -i "s/^restserver.url.*$/restserver.url=http:\/\/0.0.0.0:8080/g" ./conf/rest-server.properties

# 2. Init docker script
COPY hugegraph-server/hugegraph-dist/docker/scripts/remote-connect.groovy ./scripts
COPY hugegraph-server/hugegraph-dist/docker/scripts/detect-storage.groovy ./scripts
COPY hugegraph-server/hugegraph-dist/docker/docker-entrypoint.sh .
RUN chmod 755 ./docker-entrypoint.sh

EXPOSE 8080
VOLUME /hugegraph-server

HEALTHCHECK --interval=15s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/versions >/dev/null

ENTRYPOINT ["/usr/bin/dumb-init", "--"]
CMD ["./docker-entrypoint.sh"]
