# 当前状态 — 2026-10-06

已确认修复全部分批commit推送，11条本轮新意见reply/resolve，旧261 ordering仍open。最后两项测试fixture也已推。

- 261 b7035f8ed24e1c76c04e972890a88aece3d23ad9：57相关unit+3Auth通过；真实锁环定位后仅改normal teardown条件，旧新proof及完整77query/scan通过。最新2736发布tree与实际已测current/source完整绑定。
- 266 ddbe2c30e19cdcdf82c365b97505dc6258b19f11：当前Java生产与a8dd已测一致（format/全仓compile/70相关回归）；最后shell fixture旧失败/新完整security、upgrade、foreground通过，2732新head绑定。
- 267 8b2e13290278f88def3e1080923205df36b1266d：最新format/全仓compile/76native+MultiGraphs通过，2724blob绑定零漂移；仅既有2个HStore-only跳过。

261/266最后test-only新提交CI重新运行；267本headCI全成功。不是新head全unit/core、全服务包或生产验收。旧ordering/TTL/codec与阶段3/4、4/4仍保留，详见handoff-current.md。
