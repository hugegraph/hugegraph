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

The supported RISC-V target is 64-bit `linux/riscv64` LP64D with glibc 2.30 or newer.
This target covers HugeGraph Server with the embedded RocksDB backend only. It does not
cover musl/Alpine, 32-bit RISC-V, PD, Store, HStore, or other storage backends.

The RISC-V image uses the checksum-pinned Alibaba Dragonwell 11 Extended Server VM from
the [Dockerfile](Dockerfile). The runtime also provides `libstdc++6`, `libgcc-s1`, and
`libatomic.so.1`, which are required by the packaged RocksDB JNI library.

### Docker and QEMU

Docker Desktop includes emulation support. On Linux, install the RISC-V emulator only if
it is not already registered:

```bash
docker run --privileged --rm tonistiigi/binfmt --install riscv64
```

The smoke helper requires Docker Buildx, `curl`, and `jq` on the host. From the repository
root, build and run the complete runtime smoke test:

```bash
IMAGE=hugegraph-server:riscv64-test
docker buildx build --platform linux/riscv64 --load --tag "$IMAGE" \
  --file hugegraph-server/Dockerfile .
hugegraph-server/hugegraph-dist/src/assembly/travis/run-docker-runtime-smoke-test.sh \
  "$IMAGE" riscv64
docker image rm "$IMAGE"
```

The smoke helper removes its test containers, anonymous volumes, and temporary files. It
keeps the input image, uses the current Buildx builder, and does not remove the host
emulator. If you registered the emulator specifically for this test, remove it separately:

```bash
docker run --privileged --rm tonistiigi/binfmt --uninstall qemu-riscv64
```

Do not uninstall Docker Desktop's built-in emulator. QEMU is substantially slower than
native RISC-V and is intended for correctness testing, not performance measurements.

### Native RISC-V

Use Dragonwell 11 Extended `11.0.31.28.11` and verify its archive with the URL and SHA-256
declared in the Dockerfile. On a Debian-derived glibc system, install the native tools and
runtime libraries:

```bash
sudo apt-get update
sudo apt-get install -y maven protobuf-compiler \
  protobuf-compiler-grpc-java-plugin libatomic1 libstdc++6 libgcc-s1 curl jq
```

After selecting Dragonwell as `JAVA_HOME`, build and verify the RocksDB-only distribution:

```bash
test "$(uname -m)" = riscv64
"$JAVA_HOME/bin/java" -XshowSettings:vm -version
mvn clean package -Drocksdb-only -pl hugegraph-server/hugegraph-dist -am \
  -Dmaven.test.skip=true -Dmaven.javadoc.skip=true
hugegraph-server/hugegraph-dist/src/assembly/travis/check-rocksdb-only-dist.sh \
  hugegraph-server/apache-hugegraph-server-*/
hugegraph-server/hugegraph-dist/src/assembly/travis/run-native-runtime-smoke-test.sh \
  hugegraph-server/apache-hugegraph-server-*/
```

Run the existing Core and API gates with the same RocksDB-only dependency boundary:

```bash
hugegraph-server/hugegraph-dist/src/assembly/travis/run-core-test.sh rocksdb
hugegraph-server/hugegraph-dist/src/assembly/travis/run-api-test.sh \
  rocksdb target/riscv64-api-report
```
