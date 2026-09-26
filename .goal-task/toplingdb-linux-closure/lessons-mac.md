# Mac 开发经验

[通用经验](lessons.md) · [开发入口](mac.md)

| 触发条件 | 做法与边界 | 依据 |
| --- | --- | --- |
| 用假 runtime 或 marker spy 定位启动问题 | 可证明 provider 选择和校验调用覆盖；不能证明真实 JNI、数据库损坏或 native 生命周期 | [三项配置复现](development-handoff-history-20260926.md#tp-专项审查与-issue-更新) |
| 定向 Maven 在依赖解析阶段失败 | 核查模块 reactor 和本地父坐标；未进入编译时不能报告测试通过。旧指标测试经包含依赖的 reactor 才真正执行 | [本机验证记录](development-handoff-history-20260926.md#本机验证边界) |
| Docker CLI 或本地 Unix socket API 挂起，但 OrbStack 可用 | 对单条 Docker 命令移除 HTTP_PROXY/HTTPS_PROXY/ALL_PROXY（含小写），curl 用 --noproxy '*'；先确认本地 API，不重启或修改全局代理。该次旁路后构建与运行均恢复 | [本轮核心实测](mac.md#本机核心实测身份)，native-image-build-direct.log |
| 用 Mac amd64 容器验证 x86 JNI | 核对 CodeSource、proc maps、JAR/native hash，记录模拟架构和资源限制；此路径可证明核心正确性，不能推导性能或远端部署通过 | [身份与边界](mac.md#本机核心实测身份) |
