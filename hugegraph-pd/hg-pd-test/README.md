# PD module tests

Run common and core suites separately so native and Raft singletons do not carry
state between suite processes:

```bash
mvn test -pl hugegraph-pd/hg-pd-test -am -P pd-common-test
mvn test -pl hugegraph-pd/hg-pd-test -am -P pd-core-test
```

The core fixture uses `tmp/pd-core-data` relative to the test module by default.
Override it with `-Dpd.test.data_path=/absolute/task-owned/path` when isolating a
validation run. The fixture deletes that directory before initialization; never
point it at an existing service's data. Client and REST suites also require the
PD service setup described in the repository CI workflow.

Focused metadata shutdown checks (no external PD service needed):

```bash
mvn test -pl hugegraph-pd/hg-pd-test -am -P pd-core-test \
  -Dtest=PDLifecycleTest,PDRequestGateTest,RaftListenerDrainTest,HgKVStoreShutdownTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

These cover request/callback/snapshot drain order, failed startup, idempotence,
interruption and timeout, plus native metadata close and persistent reopen with
standard RocksDB. Run real PD processes with each packaged runtime separately;
the unit fixtures do not establish Topling service acceptance.
