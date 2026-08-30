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
# 1st stage: build source code
FROM --platform=$BUILDPLATFORM maven:3.9.0-eclipse-temurin-11 AS build

WORKDIR /pkg

COPY . .

ARG MAVEN_ARGS
ARG SOURCE_REVISION=local

RUN --mount=type=cache,id=hugegraph-maven-${SOURCE_REVISION},target=/root/.m2,sharing=locked \
    mvn install $MAVEN_ARGS -e -B -ntp -Dmaven.test.skip=true -Dmaven.javadoc.skip=true \
    && rm ./hugegraph-server/*.tar.gz ./hugegraph-pd/*.tar.gz ./hugegraph-store/*.tar.gz

# Prepare the optional ToplingDB runtime in a dedicated build target. The
# regular Maven distribution intentionally excludes lib/topling/**.
FROM build AS build-topling

RUN apt-get -q update \
    && apt-get -q install -y --no-install-recommends --no-install-suggests unzip \
    && rm -rf /var/lib/apt/lists/* \
    && SERVER_DIR=$(find /pkg/hugegraph-server -maxdepth 1 -type d \
                     -name 'apache-hugegraph-server-*' -print -quit) \
    && test -n "$SERVER_DIR" \
    && mkdir -p "$SERVER_DIR/lib/topling" \
    && cp /pkg/hugegraph-server/hugegraph-dist/src/assembly/static/lib/topling/rocksdbjni*.jar \
          "$SERVER_DIR/lib/topling/" \
    && printf '\nrocksdb.provider=topling\n' \
          >> "$SERVER_DIR/conf/graphs/hugegraph.properties" \
    && bash -c 'source hugegraph-server/hugegraph-dist/src/assembly/travis/install-rocksdb.sh server' \
    && sed -i '/^rocksdb\.provider=topling$/d' \
          "$SERVER_DIR/conf/graphs/hugegraph.properties"

# Shared runtime environment
# Note: ZGC (The Z Garbage Collector) is only supported on ARM-Mac with java > 13
FROM eclipse-temurin:11-jre-noble AS runtime

LABEL maintainer="HugeGraph Docker Maintainers <dev@hugegraph.apache.org>"

# TODO: use g1gc or zgc as default
# Note: --add-exports is required for Java 11+ to access jdk.internal.reflect for auth proxy
ENV JAVA_OPTS="-XX:+UnlockExperimentalVMOptions -XX:+UseContainerSupport -XX:MaxRAMPercentage=50 -XshowSettings:vm --add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED" \
    HUGEGRAPH_HOME="hugegraph-server" \
    STDOUT_MODE="true"

WORKDIR /hugegraph-server/

# 1. Install runtime dependencies and configure server
# Note: iproute2 provides `ss`, which the bin/util.sh port preflight needs.  The
# jammy base image ships neither ss nor netstat, so without it the preflight is
# permanently inconclusive and a duplicate start is no longer caught.  It
# replaces lsof, which no shipped script calls any more.
RUN apt-get -q update \
    && apt-get -q install -y --no-install-recommends --no-install-suggests \
       dumb-init \
       procps \
       curl \
       iproute2 \
       vim \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# 2. Init docker script
COPY hugegraph-server/hugegraph-dist/docker/scripts/remote-connect.groovy /opt/hugegraph-docker/
COPY hugegraph-server/hugegraph-dist/docker/scripts/detect-storage.groovy /opt/hugegraph-docker/
COPY hugegraph-server/hugegraph-dist/docker/docker-entrypoint.sh .
RUN chmod 755 ./docker-entrypoint.sh

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/versions >/dev/null

ENTRYPOINT ["/usr/bin/dumb-init", "--"]
CMD ["./docker-entrypoint.sh"]

# ToplingDB image: contains both providers and selects ToplingDB by default.
# Build with: docker build --platform linux/amd64 --target topling ...
FROM runtime AS topling

COPY --from=build-topling /pkg/hugegraph-server/apache-hugegraph-server-*/ /hugegraph-server/
RUN apt-get -q update \
    && apt-get -q install -y --no-install-recommends --no-install-suggests \
       libbz2-1.0 \
       liblz4-1 \
       libsnappy1v5 \
       zlib1g \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* \
    && cp /opt/hugegraph-docker/*.groovy ./scripts/ \
    && sed -i "s/^restserver.url.*$/restserver.url=http:\/\/0.0.0.0:8080/g" \
        ./conf/rest-server.properties
ENV HG_SERVER_ROCKSDB_PROVIDER="topling"
VOLUME /hugegraph-server

# Keep the standard image as the final/default target for existing builds.
FROM runtime AS standard

COPY --from=build /pkg/hugegraph-server/apache-hugegraph-server-*/ /hugegraph-server/
RUN cp /opt/hugegraph-docker/*.groovy ./scripts/ \
    && sed -i "s/^restserver.url.*$/restserver.url=http:\/\/0.0.0.0:8080/g" \
        ./conf/rest-server.properties
VOLUME /hugegraph-server
