# 完整示例机器人

这个目录提供可直接运行的 Java 8 示例，保持原项目的中文注释、事件注册和控制台输出风格，同时使用改造后的可靠异步入口。

## 文件

- `Main.java`：完整入口，凭证、连接事件、文本命令、入站媒体、卡片/反馈和优雅关闭。
- `ExampleCards.java`：五类卡片构造与更新，各字段可作为自己业务的参考。
- `ConsoleLogger.java`：自定义日志实现，不需要额外日志 provider。

## 启动

在 SDK 根目录运行：

```bash
mvn compile exec:java -Dexec.args="--help"
```

该命令只输出使用说明，不需要凭证、不连接企微。

用独立测试机器人运行：

```bash
export WECHAT_BOT_ID="your-bot-id"
export WECHAT_BOT_SECRET="your-secret"
mvn compile exec:java
```

凭证文件优先：

```bash
export WECHAT_BOT_ID_FILE="/etc/wecom/bot_id.txt"
export WECHAT_BOT_SECRET_FILE="/etc/wecom/secret.txt"
mvn compile exec:java
```

文件变量优先于直接值；`WECHAT_BOT_WS_URL` 可覆盖默认端点。Ctrl+C 关闭客户端和业务线程。

## 聊天命令

| 命令/输入 | 演示 |
| --- | --- |
| `/help` | 回复帮助 |
| 普通文本 | 结束 stream 形式的一次性文本回复 |
| `/stream 问题` | 三次累计快照，间隔三秒，不使用真实模型 |
| `/markdown` | Markdown 和关联 feedback.id |
| `/card [类型]` | 默认 button_interaction；可选 text_notice/news_notice/button_interaction/vote_interaction/multiple_interaction |
| `/push 内容` | 主动推送到触发消息的当前会话 |
| `/file`、`/image`、`/voice`、`/video` | 上传配置的本机素材并回复 |
| 图片/文件/视频消息 | 下载解密，保存并回复保存文件名 |
| 语音消息 | 使用企微已经转写的文本 |
| 图文混排消息 | 保持顺序，文本累计、图片逐项下载 |

卡片点击直接使用新事件 req_id 和原 task_id 更新，只接受同一用户/会话的已登记卡片，重复点击不执行新的示例动作。选择结果从嵌套 selected_items 读取，更新卡片保留实际提交的选项并禁用输入；反馈只记录关联 ID/类型/原因，不发送普通回复。

## 媒体配置

```bash
export WECHAT_DEMO_UPLOAD_FILE="/path/to/demo.pdf"
export WECHAT_DEMO_UPLOAD_IMAGE="/path/to/demo.png"
export WECHAT_DEMO_UPLOAD_VOICE="/path/to/demo.amr"
export WECHAT_DEMO_UPLOAD_VIDEO="/path/to/demo.mp4"
export WECHAT_DEMO_DOWNLOAD_DIR="downloads"
```

这些路径只能由本机操作者配置，聊天输入不能请求读取任意路径。未配置某类型时，其命令返回配置提示；默认 downloads 已忽略，自定义目录自行管理。

图片支持 png/jpg/jpeg/gif，语音 amr，视频 mp4；只分享演示素材。示例使用官方文档中的公开图文卡片图片，可替换自己的图片 URL。

## 并发与错误

- 耗时文本/上传示例使用自己的 4 线程、64 队列业务池。
- 模拟流式的 sleep 只发生在该业务池，SDK 收帧/ACK 不被阻塞。
- 欢迎语和卡片点击五秒内非阻塞提交，不排入长任务队列。
- 文件写入通过 thenApplyAsync 放到业务池；混排图片顺序下载，避免占满 SDK 四个媒体任务槽。
- 记录 ACK 成功和稳定错误 code；限流打印 retryAfterMs，不自动补发；UNKNOWN 不盲目重试。
- 不打印 Secret、aeskey、下载 URL 或反馈正文。

## 适用边界

这是协议演示，不是 Agent 平台、真实审批或生产身份认证。卡片记录只存在内存且最多 256 条，重启后不能更新此前登记的示例卡片；没有持久事件排重/上传恢复。SDK 的同进程上传重连续传仍可用，但旧回调 Frame 不跨代复用。

生产应用需要补齐平台身份映射、授权、msgid 排重、集群 owner、状态持久化与业务失败恢复。SDK API/配置/兼容性和验证边界见 [根 README](../../../../../../../README.md)。
