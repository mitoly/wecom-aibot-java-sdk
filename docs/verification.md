# SDK 改造与验证记录

## 范围与协议校准

本轮在现有 Java SDK 内改造，没有修改 agent-platform 业务代码。原同步公共入口保留并转接可靠异步核心；关闭后不可重启。版本 1.1.0-SNAPSHOT。

以官方 101463 为协议裁判，修正原调研依赖 SDK 推断的结论：普通回复支持 Markdown；chat_type 是合法字段；stream_with_template_card 和 stream.msg_item 当前不支持；流刷新期限十分钟、普通被动回复窗口二十四小时；欢迎语及更新卡片五秒期限；分片序号从零开始且幂等；素材上传会话三十分钟、素材三天有效。

事件按 101027 的 event.template_card_event、event.feedback_event 嵌套建模；卡片按 101032 补齐五类类型，协议测试样例见 src/test/resources/protocol。

## 离线测试

JUnit 测试涵盖模型与未知字段、全部 1..32 填充长度、损坏解密失败、分钟/小时滚动额度、真实 loopback WebSocket 的 ACK 串行及超时封禁、旧连接上下文、并发 update/finish、认证预算、心跳黑洞、被替代停止重连、事件窗口、分片重试/重连续传、下载流大小限制。可控传输补充 send=false 和旧 listener 不能清理新 pending 的回归；可控单调时钟验证十分钟期限。

最终验收日期：2026-09-18。

| 环境 | 命令 | 结果 |
| --- | --- | --- |
| JDK 8 | `mvn clean verify` | 39 项测试，0 失败、0 错误、0 跳过 |
| JDK 21 | `mvn clean install` | 39 项测试，0 失败、0 错误、0 跳过；JAR 和源码 JAR 已安装到本地 Maven 仓库 |
| 实际 agent-platform parent/BOM 的消费夹具 | `mvn verify`，目录 `src/test/consumer-smoke` | 2 项测试通过：Boot ObjectMapper/AgentScope/Reactor；OkHttp 5 实际 WebSocket subscribe、callback、reply ACK |

上述 Maven 命令实际附带临时 Central 配置 `-s /private/tmp/wecom-sdk-maven-settings.xml`。SDK class major 为 52（Java 8）。`git diff --check HEAD` 通过。

产物：`target/wecom-aibot-java-sdk-1.1.0-SNAPSHOT.jar` 与对应 `-sources.jar`。没有发布到远端 Maven 仓库，没有修改 agent-platform 的生产 POM 或业务代码。

### README 与 example 完善

2026-09-18 补齐原项目风格的 README、完整 Main、五类 ExampleCards、ConsoleLogger 和示例目录说明；增加固定版本 Exec 插件，支持 `mvn compile exec:java` 与无凭证 `--help`。

- Java 8/21 的完整构建均通过，39 项 SDK 测试无失败/错误/跳过。
- Java 8/21 的示例 `--help` 均实际运行通过，不连接企微。
- README 的 27 个 Java 代码块在临时包装上下文中用 JDK 8 编译通过，包括独立 MyBot；WebFlux 片段使用消费端 classpath。
- 修正原 MixedContent 的官方 `msg_item` 映射，保留 getItems 与旧 items 输入别名，新增顺序解析回归；平台 Boot ObjectMapper 的消费测试也验证该映射。
- 两项消费端测试继续通过；文档本地链接及 `git diff --check HEAD` 通过。

完整示例仍未用真实机器人联调；其卡片/媒体命令仅用于演示，不具生产身份认证、持久排重或工具审批能力。

### 消费端依赖管理

消费夹具直接继承现有 agent-platform parent，带入 WebFlux、AgentScope Harness/AG-UI/OpenAI 2.0.1。实测解析为 Spring Boot 3.5.0、Reactor 3.7.6、Jackson databind/core 2.19.0、annotations 2.19.1、SLF4J 2.0.17。

初次夹具带入 SDK okhttp 4.12.0 与 AgentScope 的 okhttp-jvm 5.3.2 两套同包实现；仅启动冒烟不能排除 classpath 顺序问题。夹具已将 okhttp 与 okhttp-jvm 都统一管理为 5.3.2，并断言 OkHttpClient 实际从 okhttp-jvm-5.3.2 加载、资源只有一个实现，再完成真实本地 WebSocket 通信验证。

未来集成 agent-platform 时需复制这项 dependencyManagement；独立 SDK 继续默认 OkHttp 4.12.0，Java 8 测试覆盖该默认组合。消费测试不等于平台整套 Agent/权限/集群业务联调。

本机默认 Maven 镜像 Nexus 出现 TLS 握手失败，因此验证使用只在临时目录的 Maven Central 镜像配置；未修改用户全局 settings.xml。运行 MockWebServer 需要允许 loopback 端口绑定。

## 真实企微验收

默认测试不访问企微，不使用凭证。显式 profile 仅验证隔离机器人认证与关闭：

```bash
# 环境变量预先由测试人员安全设置，使用独立测试机器人。
mvn -Plive-bot verify
```

live-bot 未提供凭证会失败，不以跳过测试伪装联调成功。其 subscribe 会替代同 bot 的其他长连接，务必使用隔离机器人。

完整人工验收：

1. 单聊文本、转写语音、图片/mixed/file/video 入站，校对明文或加密 userid 的真实格式。
2. 普通 Markdown、流式正文与结束，确认内容字节上限及刷新频率计算方式。
3. 用户先向机器人发消息，再分别验证主动单聊/群聊、明确 chat_type、媒体回复。
4. enter_chat 欢迎语、五类卡片、点击选项、同 task_id 更新及 userids 范围；验证嵌套反馈详情。
5. 单片和多片上传、断线继续、重复片、类型限制、实际 created_at 格式。
6. 同 bot 新连接替代旧连接，旧实例停止抢连；真实断网/恢复和模式切换。
7. 核对会话消息额度与机器人上传额度；SDK 本地计数是保守策略，不能代替服务端实际额度。

本轮没有隔离机器人凭证，真实企微联调未执行，不宣称线上验收通过。进程重启不恢复上传任务；ACK UNKNOWN 的普通消息不自动重试。身份映射、msgid 排重、集群所有权与工具审批由下一轮平台集成实现。
