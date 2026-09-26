# Linux 验收与恢复入口

与 [state.md](state.md) 一起读取；先确定要验收的 Mac 交付提交，再按需查看证据，不全量重跑历史清单。
本文件中的服务器环境与历史结果来自已提交记录；本轮没有连接服务器或启动服务器实验。
Mac 上的 Linux 容器核心实测身份与结果见 [mac.md](mac.md#本机核心实测身份)，不能替代此处服务器验收。

## 环境和恢复边界

- 历史工作区 `/home/soc-baidu/.codex/worktrees/f29e/hugegraph`，本地分支 codex/toplingdb-linux-validation，
  远端 org/toplingdb。旧主 checkout `/home/soc-baidu/github/hugegraph` 不作为恢复依据。
- 恢复先核对主机、工作区、HEAD、远端 URL 与未提交文件。Mac 代码经推送后再整合，禁止 force-push 或覆盖本地改动。
- 历史未提交 channel refresh 及 WAL 文件有审查未收口记录。channel refresh 属通用问题，维持独立跟进；
  WAL 当前由 Mac 基于已提交版本重新设计，不自动复用或提交 Linux 旧补丁，不自动重开旧补丁审查轮次。
- Kubernetes 历史配置为 /home/soc-baidu/.kube/config、kind-kind。只在确认资源归属后操作，
  不原位升级历史 namespace、不清理无关资源；单节点 kind 不证明物理多机或真实网络分区。
- 同一时间只运行一个重任务；Maven 全量、镜像构建、部署和故障注入不叠加。
  不提交 evidence/、数据库、镜像、原始大 JSON、数据集或凭据。

## 验收清单

本轮 Mac 交付提交见下方接收记录。以下服务器验收均未开始，不继承 Mac 或历史通过。

| 项目 | 验收场景与预期 | 状态 |
| --- | --- | --- |
| #250/#251/#253 | 直接及容器启动，默认/自定义目录和额外图；实际 JNI 与 Java provider 一致；冲突在数据库打开前失败，原数据不变 | 待服务器验收 |
| #254 | 用真实 TP JNI 经 adapter 执行多 key truncate，旧数据全空、CF 保留、可重新读写并关闭；标准 provider 对照自身预期分支 | 待服务器验收 |
| #255 | 真正运行 runtime diagnostic，检查前置探测、错误分类、原始日志和 JNI 身份；仅已知断言得到例外，其他错误阻塞 | 待服务器验收 |
| #249 | 标准/TP 确定提交分别验证 snapshot 成功与故障恢复，包含独立/嵌套 WAL、失败后重启及源文件校验 | 待服务器验收 |
| #212 | 核对真实 DB/CF 和服务生命周期的残余关闭告警，区分已知合成断言、正常关库和卡住 worker | 待归因，已有历史成功 |
| #248 | 复查 clear 后首次 Server 重启丢可见性的单次线索，固定确认写入及查询证据；第二次成功不覆盖第一次异常 | 待定位 |
| #213 | 核实不可变 JNI 坐标、源码/工具链、CPU 基线、校验和及许可/发布链 | 正式发布要求未完成 |
| #252 | 相关正确性与资源条件满足后，同源码、workload、资源和配置做标准/TP 至少三轮性能对照，保留原始结果及统计 | 未开始，仅 Linux |

多节点和部署验收在 Linux 阶段按实际拓扑记录；通用 HA 建设、图级快照或完整平台矩阵不作为所有 TP 项统一前提。
下一动作：接收下方确定代码提交并核对服务器环境；独立调查 #212/#248/#213 可继续。
服务器侧保留已有未提交内容，不自动重启服务或复用旧镜像作新源码证据。

## 本轮交接与复跑

确定代码交付 head 为 `741c64a5c3858d7d3489d209acec0935b0a8af58`：
配置组 `8054b6552e67b872e601d3d3a8cb6021bab4f1aa`，WAL/adapter
`4bf7612e2acaef4d230b9b581a2afd31591332fe`，CI/诊断为
`ceea35428a1f182cda294a93636da3dd9c2a3438`，最后 head 只补 Docker ABI probe 的空目录 fixture。
四批代码已非强制推送，远端分支与 PR head 已核对为 `741c64a5c`；
接收时 fetch 原 toplingdb 分支，
核对完整 SHA 后在干净工作树构建；文档后续提交不改变该源码。
JNI JAR SHA-256 为 `86eb1bd3d9f84ef0dddd3fe95c640a6f145ca2d5a26a5ba628f1f298a2031fae`，
native 为 `c25ff6e676290db6db47df0954640aa609c391450ec90e1a8eec1f87e174dd38`；
这是此次验收输入身份，不代表服务器已经加载或验收通过。
如果服务器存在旧未提交 WAL/channel 补丁，先保留并逐项核对，不覆盖或夹带进本轮源码。

配置组的预期是 direct/init、Docker 都以实际图配置为准，冲突在 JNI 打开前失败；
真实服务测试要核对选中 JAR、native 映射、所有 data/WAL 根与失败前后文件。
WAL 预期是完整恢复快照前数据、移除快照后数据；失败保留 checkpoint/pending，
下次正常 open 必须先重试一致安装，缺源或配置不符则失败，不能伪报成功或重放旧日志。
单 DB 挂载布局被拒绝，Compose/K8S 挂载父数据根。

定向回归命令从仓库根执行，使用本次专用临时路径并确保传给 fork JVM：

```bash
mkdir -p /tmp/topling-closure-tests
mvn test -pl hugegraph-server/hugegraph-test -am -P unit-test,rocksdb \
  -Dtest=RocksDBSessionsTest,RocksDBSnapshotRestoreTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false \
  '-DargLine=-Xmx512m -Djava.io.tmpdir=/tmp/topling-closure-tests' \
  -Djava.io.tmpdir=/tmp/topling-closure-tests -Dmaven.javadoc.skip=true -ntp

TRAVIS_DIR=hugegraph-server/hugegraph-dist/src/assembly/travis
bash "$TRAVIS_DIR/test-topling-runtime-selection.sh"
bash "$TRAVIS_DIR/test-topling-server-entrypoint-roots.sh"
bash "$TRAVIS_DIR/test-topling-provider-ownership.sh"
bash "$TRAVIS_DIR/test-topling-native-diagnostic.sh"
```

该 Maven 命令默认使用标准 JNI。TP 回归必须把测试 classpath 中标准 rocksdbjni 替换为
本次确定 TP JAR，并核对 native 映射，不能只改 provider 环境变量。
本轮真实 TP 测试使用如下 reactor classpath 方法。先按
[开发文档](../../docs/toplingdb/toplingdb-development.md) 配齐 native 系统依赖；此命令不替代镜像打包验收。
输出路径仅属于此次测试，构建必须成功，不使用旧 classpath 或旧 classes。

```bash
mvn compile dependency:build-classpath -pl hugegraph-server/hugegraph-test -am \
  -Dmdep.outputFile=/tmp/topling-closure-tests/reactor-classpath.txt \
  -Dmaven.javadoc.skip=true -ntp
python3 - <<'PYCP'
from pathlib import Path
root = Path.cwd()
base = Path('/tmp/topling-closure-tests')
cp = (base / 'reactor-classpath.txt').read_text().strip().split(':')
standard = [p for p in cp if '/org/rocksdb/rocksdbjni/' in p]
assert len(standard) == 1, standard
tp = root / 'hugegraph-server/hugegraph-dist/src/assembly/static/lib/topling/rocksdbjni-8.10.2-20260725.141011-1.jar'
assert tp.is_file()
cp = [str(tp) if p == standard[0] else p for p in cp]
cp.insert(0, str(root / 'hugegraph-server/hugegraph-test/target/classes'))
(base / 'tp-classpath.txt').write_text(':'.join(cp))
PYCP
TP_JAR="$PWD/hugegraph-server/hugegraph-dist/src/assembly/static/lib/topling/rocksdbjni-8.10.2-20260725.141011-1.jar"
TP_NATIVE_DIR=/tmp/topling-closure-tests/native
mkdir -p "$TP_NATIVE_DIR"
unzip -p "$TP_JAR" librocksdbjni-linux64.so > "$TP_NATIVE_DIR/librocksdbjni-linux64.so"
sha256sum "$TP_JAR" "$TP_NATIVE_DIR/librocksdbjni-linux64.so"
ldd "$TP_NATIVE_DIR/librocksdbjni-linux64.so"
cat > /tmp/topling-closure-tests/ToplingCoreProbe.java <<'JAVA'
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.rocksdb.RocksDB;
public class ToplingCoreProbe {
    public static void main(String[] args) throws Exception {
        Class.forName("org.rocksdb.SidePluginRepo");
        RocksDB.loadLibrary();
        String maps = new String(Files.readAllBytes(Paths.get("/proc/self/maps")));
        String library = Paths.get(System.getenv("LD_PRELOAD")).toRealPath().toString();
        if (!maps.contains(library)) throw new AssertionError("TP native not mapped");
        System.out.println("TP JAR: " + RocksDB.class.getProtectionDomain().getCodeSource().getLocation());
        Class<?> type = Class.forName(args[0]);
        Result result = "all".equals(args[1]) ? new JUnitCore().run(type) :
                        new JUnitCore().run(Request.method(type, args[1]));
        for (Failure failure : result.getFailures()) System.err.println(failure.getTrace());
        System.out.printf("Tests=%d Failures=%d Ignored=%d%n", result.getRunCount(),
                          result.getFailureCount(), result.getIgnoreCount());
        if (!result.wasSuccessful() || result.getRunCount() == 0) System.exit(1);
    }
}
JAVA
TP_TEST_CP=$(cat /tmp/topling-closure-tests/tp-classpath.txt)
for target in 'org.apache.hugegraph.backend.store.rocksdb.RocksDBSnapshotRestoreTest all' \
              'org.apache.hugegraph.unit.rocksdb.RocksDBSessionsTest testAdapterToplingTruncateWithMultipleKeys'; do
  read -r test_class test_method <<< "$target"
  TOPLINGDB_EASY_MIGRATE_CONF="$PWD/hugegraph-server/hugegraph-dist/src/assembly/static/conf/toplingdb.yaml" \
  LD_PRELOAD="$TP_NATIVE_DIR/librocksdbjni-linux64.so" LD_LIBRARY_PATH="$TP_NATIVE_DIR" \
    java -Xmx512m -cp "$TP_TEST_CP" /tmp/topling-closure-tests/ToplingCoreProbe.java \
      "$test_class" "$test_method"
done
```

检查 ldd 不得含 not found；实际 JAR/native hash 必须匹配交付身份，不满足时先修环境。
预期 helper 24/0/0、adapter 1/0/0。服务器需再记录自身产物和结果。

对已构建的 Topling component（Server、PD、Store 分别执行），运行仓库 wrapper：

```bash
COMPONENT_DIR=/absolute/path/to/built/topling-component
bash "$TRAVIS_DIR/run-topling-native-diagnostic.sh" /tmp/topling-native-diagnostic \
  bash "$TRAVIS_DIR/test-rocksdb-runtime.sh" topling "$COMPONENT_DIR"
```

期望 probe exit 0 且 complete；CF lifecycle 若通过则报告 passed，若严格匹配已知 #212 断言且
exit 134 则报告 known-cf-assertion。任何前置、其他错误或混合失败必须 wrapper exit 1。
已知合成断言例外不代表真实服务生命周期关闭告警消失。镜像打包、服务重启、正式发布、性能和必要
多节点验收仍须记录独立实际结果；本轮没有为所有项目强加统一 3+3+3 前提。

正式 Docker/服务矩阵尚未全部通过；`ceea35428` 的旧运行包含失败与待定，不能继承为当前 head 通过。
Server ABI probe 的默认空目录 fixture 已补齐、本机复现验证并推送 `741c64a5c`；
文档交付前的 `741c64a5c` 检查仍有 Commons 失败及相关 job 待定；图片 LF 修复在本次
文档批次发布后才进入远端，需按包含修复的 SHA 再核对，不能把本机核心实测当作矩阵已通过。
其余 hg-pd-test 缺 PDService/IndexAPI 编译失败保留独立门禁，未在 TP 范围修改通用依赖。

## 每次验收的证据

记录 issue、源码 SHA/是否干净、构建输入、镜像 digest 和 revision、实际 JNI 路径及 hash、
配置、拓扑/namespace、命令、退出码、计数与 skip、前后数据断言、未覆盖边界和原始证据位置。
镜像标签、Pod Ready 或重试后成功不能单独代表通过；失败项目保留失败证据和解除条件。
Mac 完成与 Linux 完成分别记录，不用此文件的待验状态阻止独立 Mac 工作。

## 历史结果摘要

- #212 已有 938e4b9c4、7afe25947 生命周期修复，以及旧 SHA 三轮真实验证；不能说从未验证。
- e109012a0 已修复 memtable_as_log_index 配置兼容；a35ebeb17 标准/TP standalone snapshot 成功路径
  曾证明快照前数据保留、快照后消失。它们不证明本轮 #249 的失败安全。
- a35 单机、1+1+1、3+3+3 有 CRUD、重启、JNI、固定子集数据等局部证据；仍有 db not closed 告警。
  PD 曾升级到 327737f16，Server/Store 仍可能是 a35，必须逐组件核对，不能写成同一整体版本。
- clear 后一次首次 Server 重启查询为空沿 #248；旧 Store IP 残余连接沿通用 #245，后续读到数据不证明根因修复。
- HStore snapshot_create 曾返回 500，不等同 standalone snapshot；网络分区未完成，benchmark 未开始。
- 全量 LAW 曾因数据规模和共享验证存储后置；固定子集 1000000 顶点、2098771 边的成功不代表全量或失败重试覆盖。
- 旧 WAL 20 项通过只是未提交版本的局部测试，最新差异未完成审查，不可当作交付依据。

原始证据逻辑位置见 [证据索引](evidence-index.md)；完整提交、镜像身份和时间线见
[历史日志](state-history-20260926.md)，历史勾选及终态见 [历史清单](todo-history-20260926.md)，
旧环境快照见 [历史环境](local-status-history-20260926.md)。仅在调查具体问题时读取。
