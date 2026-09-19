# Changelog

## 1.2.0-SNAPSHOT

基于官方文档 101463 全量比对审查后的修复批次。对外公开 API 签名零破坏、未新增枚举值；deprecated 同步方法签名保持不变。

### A 类：纯放宽/纯兜底（已接入方只会受益，无需适配）

- 流式续帧携带 feedback 不再被本地拒绝（官方无"仅首帧"限制），仅记 warn 日志
- 视频消息 title/description 超长改为 UTF-8 码点安全截断（官方语义为自动截断）
- 上传初始化不再强制 `total_chunks == ceil(total_size/512KB)`，仅要求单片≤512KB 且总数 1..100
- 客户端关闭竞态下在途请求 future 保证完成、容量保证释放
- 主动推送/上传分片不再受重连瞬间 generation 校验误杀（STALE_CONTEXT）
- 下载超时独立配置 `downloadConnectTimeoutMs`/`downloadReadTimeoutMs`（默认 30s/60s）
- `uploadMediaAsync(type, Path)` 校验前置到调用线程；根路径等无效路径返回 INVALID_ARGUMENT 而非 NPE
- 上传 finish 帧在确定未发出（NOT_READY/SEND_FAILED）时自动重试
- 流式会话槽位在 finish 被接受后即时回收，长连接不再累积占满容量

### B 类：错误码/事件分布/默认值变化（如有针对性分支需 review）

- 回复 ACK 超时默认 5s→15s；ACK 等待时长不超过剩余回复窗口
- 帧已写出而结果未知（ACK 超时、连接中断、无效 errcode）的 req_id 一律禁止续发（跨重连），UNKNOWN 错误消息附恢复指引
- 认证期网络中断/超时不再消耗认证失败预算（仅订阅 ACK 明确拒绝才计入），改走重连预算
- error 事件恢复"仅异常场景"语义：常规断线只发 disconnected；回调队列溢出逐条发 error 事件（含 msgid）
- 媒体任务池下载/上传分离（各 4 槽），慢下载不再饿死上传；下载排队超过回调 url 5 分钟有效期直接失败且错误可区分
- 同步等待统一抛 `AiBotException(UNKNOWN)`：不再重映射为 TimeoutException（原形态会诱导重试导致消息重复发送）
- `maxReconnectAttempts` 语义与 1.1.0 前不同：-1=无限、0=断线一次即 FAILED（旧版 0/负均表示无限）
- 流式 10 分钟窗口与 req_id 毒化状态跨重连保持（原挂在连接代上，重连即清零）

### C 类：协议假设变更

- 无（旧回调 req_id 跨重连回复的放开以 `allowStaleGenerationReply` 开关提供，待真实企微联调验证后启用，默认关闭）
