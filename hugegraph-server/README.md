# HugeGraph Server

HugeGraph Server consists of two layers of functionality: the graph engine layer, and the storage layer.

- Graph Engine Layer:
  - REST Server: Provides a RESTful API for querying graph/schema information, supports the [Gremlin](https://tinkerpop.apache.org/gremlin.html) and [Cypher](https://en.wikipedia.org/wiki/Cypher) query languages, and offers APIs for service monitoring and operations.
  - Graph Engine: Supports both OLTP and OLAP graph computation types, with OLTP implementing the [Apache TinkerPop3](https://tinkerpop.apache.org) framework.
  - Backend Interface: Implements the storage of graph data to the backend.

- Storage Layer:
  - Storage Backend: Supports multiple built-in storage backends (RocksDB/Memory/HStore/HBase/...) and allows users to extend custom backends without modifying the existing source code.

## Docker

### Standalone Mode

```bash
docker run -itd --name=hugegraph -p 8080:8080 hugegraph/hugegraph:1.7.0
```

> Use release tags (e.g., `1.7.0`) for stable deployments. The `latest` tag is intended for testing or development only.

### Distributed Mode (PD + Store + Server)

For a full distributed deployment, use the compose file in the `docker/` directory at the repository root:

```bash
cd docker
HUGEGRAPH_VERSION=1.7.0 docker compose -f docker-compose-3pd-3store-3server.yml up -d
```

See [docker/README.md](../docker/README.md) for the full setup guide.

## RISC-V Development and Testing

The RISC-V validation target is 64-bit `linux/riscv64` LP64D with glibc 2.30 or newer,
HugeGraph Server, and the embedded RocksDB backend. PD, Store, HStore, other backends,
musl/Alpine, and 32-bit RISC-V are out of scope.

The dedicated [RISC-V Server CI](../.github/workflows/riscv64-ci.yml) runs a RocksDB-only
native build and runtime smoke test in an isolated QEMU environment. It uses the
checksum-pinned Alibaba Dragonwell 11 Extended Server VM and installs `libatomic1` for the
packaged RocksDB JNI library. QEMU is for correctness testing and is not a performance
benchmark.

The repository Dockerfile and published HugeGraph image do not include RISC-V support.
Image publication requires separate multi-architecture design and validation.

For the same validation on a native Debian-derived RISC-V system, use Dragonwell 11
Extended `11.0.31.28.11` with the archive checksum declared in the CI workflow, then
install the build and runtime dependencies:

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl jq libatomic1 libgcc-s1 libstdc++6 \
  lsof maven procps protobuf-compiler protobuf-compiler-grpc-java-plugin
```

After selecting Dragonwell as `JAVA_HOME`, build and verify the RocksDB-only distribution:

```bash
test "$(uname -m)" = riscv64
"$JAVA_HOME/bin/java" -XshowSettings:vm -version
mvn clean package -Drocksdb-only -pl hugegraph-server/hugegraph-dist -am \
  -P riscv64-protobuf-tools \
  -Dmaven.test.skip=true -Dmaven.javadoc.skip=true
hugegraph-server/hugegraph-dist/src/assembly/travis/check-rocksdb-only-dist.sh \
  hugegraph-server/apache-hugegraph-server-*/
hugegraph-server/hugegraph-dist/src/assembly/travis/run-native-runtime-smoke-test.sh \
  hugegraph-server/apache-hugegraph-server-*/
```
