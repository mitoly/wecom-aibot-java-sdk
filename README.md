# 企业微信智能机器人 Java SDK

企业微信智能机器人 WebSocket 长连接 Java SDK，最初基于 [社区 Go SDK](https://github.com/evinvie/wecom-aibot-go-sdk) 移植。当前 `1.1.0-SNAPSHOT` 在原项目上重构连接管理、ACK、流式会话及媒体传输，并按官方长连接协议补齐消息、事件与模板卡片模型。

保持 Java 8+、原 Maven 坐标和 `com.wecom.aibot` 包名。既可用于普通 Java 应用，也可在 Spring Boot/WebFlux 中使用异步接口接入。

官方文档：[长连接接入](https://developer.work.weixin.qq.com/document/path/101463) · [接收事件](https://developer.work.weixin.qq.com/document/path/101027) · [模板卡片](https://developer.work.weixin.qq.com/document/path/101032)。

## 功能特性

- ✅ **WebSocket 长连接管理**：连接/认证状态区分、连接代隔离、断线重连、指数退避与抖动。
- ✅ **身份认证**：自动发送 `aibot_subscribe`，认证超时与独立重试预算。
- ✅ **心跳保活**：应用层 JSON `ping`，匹配 ACK，连续缺失时断开并重连。
- ✅ **可靠消息发送**：同 `req_id` 串行等待 ACK，检查队列拒绝、服务端错误与超时。
- ✅ **异步与同步入口**：推荐 `CompletionStage` 接口，保留旧同步方法签名。
- ✅ **消息/事件分发**：文本、图片、图文、转写语音、文件、视频及嵌套交互事件。
- ✅ **流式消息**：累计正文快照、终帧 ACK、十分钟期限、并发更新/结束排序。
- ✅ **五类模板卡片**：通知、图文、按钮、投票、多项选择，点击事件和指定用户更新。
- ✅ **主动推送**：单聊/群聊 Markdown、卡片与媒体，支持明确 `chat_type`。
- ✅ **媒体处理**：下载、文件名解析、大小限制、AES-256-CBC 与 32 字节填充严格校验。
- ✅ **分片上传**：三阶段上传、并发、分片重试、同进程网络重连续传。
- ✅ **本地限流**：分钟/小时滚动窗口，超额立即失败并返回 `retryAfterMs`。
- ✅ **可扩展模型**：未知协议字段保留，原始 `Frame.body` 可读取。
- ✅ **完整演示程序**：命令式流回复、五类卡片、反馈、媒体和主动推送。

**协议边界**：当前长连接不支持 `stream_with_template_card` 或 **回复 stream 内的** `msg_item`，SDK 会本地拒绝。入站图文的 `mixed.msg_item` 是支持的，两者不同。

## 目录

- [快速开始](#快速开始)
- [完整示例机器人](#完整示例机器人)
- [客户端与连接生命周期](#客户端与连接生命周期)
- [消息回复](#消息回复)
- [流式回复](#流式回复)
- [模板卡片与点击事件](#模板卡片与点击事件)
- [欢迎语与反馈](#欢迎语与反馈)
- [主动推送](#主动推送)
- [媒体上传下载](#媒体上传下载)
- [事件列表](#事件列表)
- [配置项](#配置项)
- [错误处理限流与线程安全](#错误处理限流与线程安全)
- [同步接口兼容](#同步接口兼容)
- [Spring Boot/WebFlux 与 agent-platform](#spring-bootwebflux-与-agent-platform)
- [测试与常见问题](#测试与常见问题)

## 快速开始

### 环境要求

- Java 8+、Maven 3.6+。
- 一个已开启「API 模式 → 长连接」的企业微信智能机器人。
- 从管理端获取 BotID 和 Secret；服务所在环境能够出站访问 `wss://openws.work.weixin.qq.com`。

同一机器人只能保持一个有效长连接。新订阅会替代旧连接，测试时使用独立机器人。长连接模式不需要配置公网消息回调 URL。

### 构建与添加依赖

```bash
# 在 SDK 项目根目录执行
mvn clean verify
mvn install
```

当前版本是本地/内部维护的快照版本，需要先安装到消费机器的 Maven 仓库，或由内部仓库提供。消费项目加入：

```xml
<dependency>
    <groupId>com.wecom</groupId>
    <artifactId>wecom-aibot-java-sdk</artifactId>
    <version>1.1.0-SNAPSHOT</version>
</dependency>
```

SDK 使用 OkHttp 4.12.0、Jackson 2.19.1 和 SLF4J 2.0.12，不传递日志实现。普通应用可自行配置兼容的日志实现；完整示例使用自己的 `ConsoleLogger`，可以直接看到生命周期和 ACK 日志。

### 基本使用

下面是可独立放入 Java 应用的完整最小示例：

```java
import com.wecom.aibot.Constants;
import com.wecom.aibot.Options;
import com.wecom.aibot.WeComAiBotClient;
import com.wecom.aibot.model.MsgCallbackBody;

public class MyBot {
    public static void main(String[] args) throws Exception {
        Options options = new Options()
                .setBotId(System.getenv("WECHAT_BOT_ID"))
                .setSecret(System.getenv("WECHAT_BOT_SECRET"));

        try (WeComAiBotClient client = new WeComAiBotClient(options)) {
            client.on(Constants.EVENT_AUTHENTICATED, (frame, payload) -> {
                System.out.println("==> 认证成功，业务已就绪");
            });

            client.on(Constants.EVENT_MESSAGE_TEXT, (frame, payload) -> {
                MsgCallbackBody message = (MsgCallbackBody) payload;
                // 非阻塞提交；成功只在服务端 ACK 确认之后返回。
                client.replyTextAsync(frame, "收到：" + message.getText().getContent())
                        .whenComplete((ack, error) -> {
                            if (error != null) {
                                System.err.println("回复未确认，请记录错误并按业务策略处理");
                            }
                        });
            });

            Thread hook = new Thread(client::close, "mybot-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                client.run(); // 阻塞到关闭、重试耗尽或被替代。
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignored) {
                    // JVM 正在退出，hook 会完成幂等关闭。
                }
            }
        }
    }
}
```

### 从文件读取凭证

```java
Options options = new Options()
        .setBotIdFile("/etc/wecom/bot_id.txt")
        .setSecretFile("/etc/wecom/secret.txt");
```

文件配置优先于直接赋值，内容按 UTF-8 读取并 trim；建议仅 owner 可读写。构造客户端时复制并校验配置，后续修改原 Options 不影响已经运行的客户端。不要提交凭证文件或在日志中打印 Secret/aeskey。

## 完整示例机器人

源码位于 [`src/main/java/com/wecom/aibot/example/`](src/main/java/com/wecom/aibot/example/README.md)：

| 文件 | 用途 |
| --- | --- |
| [Main.java](src/main/java/com/wecom/aibot/example/Main.java) | 环境变量、生命周期、命令、消息/事件、业务执行器与退出 |
| [ExampleCards.java](src/main/java/com/wecom/aibot/example/ExampleCards.java) | 五类卡片及点击后的更新构造 |
| [ConsoleLogger.java](src/main/java/com/wecom/aibot/example/ConsoleLogger.java) | 无需日志实现依赖的控制台日志 |

### 运行

```bash
# 以下为演示占位值，替换为独立测试机器人凭证。
export WECHAT_BOT_ID="your-bot-id"
export WECHAT_BOT_SECRET="your-secret"
mvn compile exec:java
```

也可使用文件：

```bash
export WECHAT_BOT_ID_FILE="/etc/wecom/bot_id.txt"
export WECHAT_BOT_SECRET_FILE="/etc/wecom/secret.txt"
mvn compile exec:java
```

只查看使用说明，不需要凭证，也不连接企微：

```bash
mvn compile exec:java -Dexec.args="--help"
```

`exec-maven-plugin` 已配置默认 mainClass。按 Ctrl+C 关闭机器人、连接和示例业务线程池。

### 机器人命令

| 聊天消息 | 演示行为 |
| --- | --- |
| 普通文本 | 一次性文本回复，底层 stream+finish |
| `/help` | 返回机器人使用帮助 |
| `/stream 介绍一下 SDK` | 发送首帧，间隔三秒刷新累计正文，等待终帧 ACK |
| `/markdown` | 标题、表格、强调、代码与 feedback.id |
| `/card` | 默认按钮交互卡片 |
| `/card text_notice` | 文本通知、导航与右上角菜单 |
| `/card news_notice` | 图片展示、导航与菜单 |
| `/card button_interaction` | 下拉选择、确认/取消与菜单 |
| `/card vote_interaction` | 多选投票及提交 |
| `/card multiple_interaction` | 两个下拉框及提交 |
| `/push 测试通知` | 使用主动推送入口发送到当前单聊/群聊 |
| `/file`、`/image`、`/voice`、`/video` | 上传本机配置的演示素材，再回复 media_id |
| 直接发送图片/文件/视频 | 下载解密、写入本地目录并反馈文件名 |
| 直接发送语音 | 回复企微已经转写的文本 |
| 发送图文混排 | 按顺序处理文本和图片，图片逐项下载 |

群聊先 @机器人；示例可移除简单的开头 `@称呼 `。入站图片、语音、文件和视频按官方文档仅支持单聊。示例不调用真实模型，也不执行平台工具/审批。

### 示例专用环境变量

| 环境变量 | 用途 |
| --- | --- |
| WECHAT_BOT_ID / WECHAT_BOT_SECRET | 直接凭证 |
| WECHAT_BOT_ID_FILE / WECHAT_BOT_SECRET_FILE | 凭证文件，优先于直接凭证 |
| WECHAT_BOT_WS_URL | 可选自定义端点 |
| WECHAT_DEMO_DOWNLOAD_DIR | 下载保存目录，默认 `downloads` |
| WECHAT_DEMO_UPLOAD_FILE | `/file` 使用的本机演示文件 |
| WECHAT_DEMO_UPLOAD_IMAGE | `/image` 使用的 png/jpg/jpeg/gif |
| WECHAT_DEMO_UPLOAD_VOICE | `/voice` 使用的 amr |
| WECHAT_DEMO_UPLOAD_VIDEO | `/video` 使用的 mp4 |

```bash
export WECHAT_DEMO_UPLOAD_FILE="/path/to/demo-report.pdf"
export WECHAT_DEMO_UPLOAD_IMAGE="/path/to/demo-image.png"
export WECHAT_DEMO_UPLOAD_VOICE="/path/to/demo-voice.amr"
export WECHAT_DEMO_UPLOAD_VIDEO="/path/to/demo-video.mp4"
export WECHAT_DEMO_DOWNLOAD_DIR="downloads"
```

这些环境变量由示例 Main 读取，SDK 本身通过 Options/方法参数配置。聊天命令不接受任意本机文件路径。上传素材应是允许分享的演示文件；默认 `downloads/` 已加入 `.gitignore`，自定义目录自行管理。

## 客户端与连接生命周期

### 构造与事件注册

```java
WeComAiBotClient client = new WeComAiBotClient(options);

// 可选自定义日志，也可用 AiBotLogger.NopLogger。
WeComAiBotClient another = new WeComAiBotClient(options, customLogger);

EventEmitter.Disposable subscription = client.on(Constants.EVENT_MESSAGE_TEXT,
        (frame, payload) -> System.out.println("收到文本消息"));
subscription.dispose(); // 可重复调用，幂等取消。
```

构造不连接企微；不要为同一机器人同时启动上述两个客户端。一般在连接前注册全部 handler。

### 启动与关闭

```java
client.startAsync()
        .whenComplete((unused, error) -> {
            if (error == null) {
                System.out.println("首次认证成功");
            }
        });

// 也可以阻塞启动：client.run();
// 应用退出时：client.close(); 或兼容 client.disconnect();
```

| 状态 | 含义 |
| --- | --- |
| STOPPED | 已构造，尚未启动 |
| CONNECTING | 建立 WebSocket |
| AUTHENTICATING | 物理连接已打开，正在等待订阅 ACK |
| READY | 已认证，允许业务发送 |
| BACKOFF | 故障后的退避等待 |
| SUPERSEDED | 被新连接替代，停止自重连 |
| FAILED | 认证/网络重试预算耗尽 |
| CLOSED | 显式关闭 |

`startAsync()` 在首次 READY 时成功；之后的重连状态通过事件报告。重复 start 不会新建连接。`isConnected()` 为 AUTHENTICATING/READY 时返回 true，业务入口应检查 `getState()==READY`。

FAILED、SUPERSEDED、CLOSED 是终态，恢复需要上层在确认 owner/配置正确后新建 client。`close()` 幂等，不支持关闭后 restart。`run()` 被中断会关闭资源并保留中断标记。

## 消息回复

以下片段中的 `client` 为已启动客户端，`frame` 必须是该客户端收到的原始回调 Frame。

### 纯文本与 Markdown

```java
client.on(Constants.EVENT_MESSAGE_TEXT, (frame, payload) -> {
    MsgCallbackBody message = (MsgCallbackBody) payload;
    client.replyTextAsync(frame, "你好：" + message.getText().getContent());
});
```

```java
client.replyMarkdownAsync(frame, "# 查询结果\n**状态**：完成\n\n[官方文档](https://developer.work.weixin.qq.com)")
        .whenComplete((ack, error) -> {
            if (error == null) {
                System.out.println("Markdown 已被服务端接受");
            }
        });
```

普通回复支持 Markdown；纯文本便捷方法编码为一次性 stream+finish=true。content 按 UTF-8 字节计算，最长 20480 字节；SDK 不自动截断。

### 通用回复体

```java
ReplyBody body = new ReplyBody();
body.setMsgType(Constants.MSG_TYPE_MARKDOWN);
body.setMarkdown(new MarkdownContent("这是通过通用 ReplyBody 发出的消息"));
client.replyAsync(frame, body);
```

普通回复用于 `aibot_msg_callback`，保持该回调 req_id；欢迎语和点击更新使用专门入口。不要手工生成新 req_id 来回复原回调。

## 流式回复

### StreamSession 方式

```java
StreamSession stream = client.newStream(frame);
stream.updateAsync("正在查询...")
        .thenCompose(ack -> stream.updateAsync("正在查询...\n已找到两项结果"))
        .thenCompose(ack -> stream.finishAsync("完整结果：\n1. 第一项\n2. 第二项"))
        .whenComplete((ack, error) -> {
            if (error == null) {
                System.out.println("流已完成：" + stream.getId());
            }
        });
```

示例机器人 `/stream` 在独立业务线程等待三秒后更新，便于观察实际流刷新。真实模型要累计 token delta，再按发送节奏提交全量正文；不要把单个 delta 当作完整 content 覆盖。

| 方法/状态 | 说明 |
| --- | --- |
| updateAsync(content) | 提交中间完整快照 |
| finishAsync(content) | 提交最终快照与 finish=true |
| update/finish | 同步会话方法，可在调用方业务线程使用 |
| getId/getElapsedMs/getRemainingMs | 流 ID 与时间辅助 |
| OPEN | 可继续提交 |
| FINISHING | 终帧等待 ACK，拒绝新更新 |
| FINISHED | 终帧 ACK 成功 |
| UNKNOWN | 发送结果未知，禁止继续使用该流 |

明确拒绝/本地失败后结束状态回到 OPEN，可在满足额度/期限时再尝试；UNKNOWN 不自动重发。会话辅助从首次提交开始保守计时，底层核心从首次实际发送开始执行十分钟上限。

### 指定 stream.id 与反馈

```java
String streamId = WeComAiBotClient.generateReqId("stream");
ReplyFeedback feedback = new ReplyFeedback();
feedback.setId(WeComAiBotClient.generateReqId("feedback"));

client.replyStreamAsync(frame, streamId, "开始处理...", false, feedback)
        .thenCompose(ack -> client.replyStreamAsync(frame, streamId, "开始处理...\n已完成", true));
```

同一回答保持 stream.id，相同回调保持 req_id。feedback 只在首帧设置；回复 stream 不支持 msg_item 或流式卡片组合。

SDK 不自动合并快照或等待额度。应用应控制刷新频率、限制在途更新，并处理分钟/小时额度和终帧失败。

## 模板卡片与点击事件

### 按钮交互卡片

```java
TemplateCard card = new TemplateCard();
card.setCardType("button_interaction");
card.setTaskId(WeComAiBotClient.generateReqId("task"));
card.setMainTitle(new CardTitle("操作确认", "请选择要执行的演示操作"));
card.setButtonList(Arrays.asList(
        new CardButton("确认", 1, "confirm"),
        new CardButton("取消", 2, "cancel")));
client.replyTemplateCardAsync(frame, card);
```

完整五类构造见 [ExampleCards.java](src/main/java/com/wecom/aibot/example/ExampleCards.java)。

| card_type | 主要模型 |
| --- | --- |
| text_notice | CardTitle、CardKV、CardAction、CardJumpAction、CardActionMenu |
| news_notice | CardImage、CardImageTextArea、verticalContentList、CardAction |
| button_interaction | CardSelectionItem、CardButton |
| vote_interaction | CardCheckbox、CardOption、CardSubmitButton |
| multiple_interaction | selectList、CardSelectionItem、CardSubmitButton |

### 点击后更新

```java
client.on(Constants.EVENT_TEMPLATE_CARD, (clickFrame, payload) -> {
    EventCallbackBody event = (EventCallbackBody) payload;
    TemplateCardEvent detail = event.getEvent().getTemplateCardEvent();

    TemplateCard updated = new TemplateCard();
    updated.setCardType(detail.getCardType());
    updated.setTaskId(detail.getTaskId()); // 保持原 task_id。
    updated.setMainTitle(new CardTitle("已收到操作", detail.getEventKey()));

    client.updateTemplateCardAsync(clickFrame, updated,
            Collections.singletonList(event.getFrom().getUserId()));
});
```

该片段展示最小调用关系，完整布局更新与操作者检查见 Main/ExampleCards。更新必须使用**本次点击事件**的 Frame，五秒内发出，并保持原 task_id。省略 userIds 表示更新所有相关用户；指定列表可限制范围。

实际事件结构：

```text
body.event.template_card_event.card_type
body.event.template_card_event.event_key
body.event.template_card_event.task_id
body.event.template_card_event.selected_items.selected_item[].question_key
body.event.template_card_event.selected_items.selected_item[].option_ids.option_id[]
```

```java
SelectedItems submitted = detail.getSelectedItems();
if (submitted != null && submitted.getSelectedItem() != null) {
    for (SelectedItem item : submitted.getSelectedItem()) {
        String questionKey = item.getQuestionKey();
        List<String> optionIds = item.getOptionIds().getOptionId();
        // 根据 questionKey/optionIds 处理业务，不相信客户端提供的工具参数。
    }
}
```

task_id 最长 128 UTF-8 字节，只能包含数字、字母和 `_-@`，业务层负责唯一性。SDK 校验更新与点击 task_id 一致，但不决定审批权限。示例只接受本进程登记、同一用户/会话的卡片，重复点击不再执行新的演示操作；它不替代生产审批记录或重启恢复。

## 欢迎语与反馈

### 文本或卡片欢迎语

```java
client.on(Constants.EVENT_ENTER_CHAT, (frame, payload) -> {
    ReplyBody welcome = new ReplyBody();
    welcome.setMsgType(Constants.MSG_TYPE_TEXT);
    welcome.setText(new TextContent("您好！有什么可以帮您的吗？"));
    client.replyWelcomeAsync(frame, welcome);
});
```

也可以将 msgtype 设为 template_card 并填入 templateCard。欢迎语仅用于 enter_chat，需五秒内发送；不要先执行耗时模型或把事件排到长业务队列。

### 给 Markdown 设置 feedback.id

```java
ReplyFeedback feedback = new ReplyFeedback();
feedback.setId(WeComAiBotClient.generateReqId("feedback"));
MarkdownContent content = new MarkdownContent("这是带反馈关联 ID 的答案");
content.setFeedback(feedback);

ReplyBody reply = new ReplyBody();
reply.setMsgType(Constants.MSG_TYPE_MARKDOWN);
reply.setMarkdown(content);
client.replyAsync(frame, reply);
```

卡片使用 `card.setFeedback(feedback)`；流仅首帧设置。

```java
client.on(Constants.EVENT_FEEDBACK, (frame, payload) -> {
    FeedbackEvent feedbackEvent = ((EventCallbackBody) payload).getEvent().getFeedbackEvent();
    String feedbackId = feedbackEvent.getId();
    Integer type = feedbackEvent.getType(); // 1 准确、2 不准确、3 取消反馈。
    String comment = feedbackEvent.getContent();
    List<Integer> reasons = feedbackEvent.getInaccurateReasonList();
    // 保存与对应 run/答案的关联。反馈事件不发送普通回复。
});
```

未设置反馈 ID 的回复，不会产生关联反馈回调。反馈详情位于 event.feedback_event，而不是 event.feedback_val。

## 主动推送

单聊目标为 userid，群聊目标为回调得到的 chatid。主动消息生成新的 req_id，不依赖原回调 Frame。

```java
client.sendMarkdownAsync("userid", Constants.CHAT_TYPE_INT_SINGLE, "**任务完成**\n请查看处理结果");
client.sendMarkdownAsync("chatid", Constants.CHAT_TYPE_INT_GROUP, "群聊通知内容");
```

```java
SendMsgBody body = new SendMsgBody();
body.setChatId("chatid");
body.setChatType(Constants.CHAT_TYPE_INT_GROUP);
body.setMsgType(Constants.MSG_TYPE_CARD);
body.setTemplateCard(card);
client.sendMessageAsync(body);
```

chat_type=1 单聊、2 群聊；0/缺省兼容且优先群聊，建议明确指定。该会话必须曾向机器人发送消息，资格以服务端 ACK 为准；SDK 不因本进程缺少历史就拒绝合法历史会话。

`/push 内容` 只推送到触发命令的当前会话，示例不会在启动时自动向任意目标推送。

## 媒体上传下载

### 上传并回复

```java
client.uploadMediaAsync("file", Paths.get("/path/to/report.pdf"))
        .thenCompose(upload -> client.replyMediaAsync(frame, "file", upload.getMediaId()))
        .whenComplete((ack, error) -> {
            if (error != null) {
                System.err.println("上传或回复未确认");
            }
        });
```

字节数组也可以上传：

```java
client.uploadMediaAsync("image", "picture.png", imageBytes);
```

返回 `UploadedMedia`：type、mediaId、createdAt（秒级 Unix 时间戳）；created_at 兼容数字或字符串。素材有效期三天。

| 媒体类型 | 文件上限 | 声明格式 |
| --- | --- | --- |
| file | 20MiB | 普通文件 |
| image | 10MiB | png/jpg/jpeg/gif |
| voice | 2MiB | amr |
| video | 10MiB | mp4 |

所有文件至少 5 字节；SDK 校验大小和文件名声明格式，真实编码由服务端校验。filename 最长 256 UTF-8 字节且不接受路径段。

### 视频参数与主动媒体

```java
client.replyMediaAsync(frame, "video", mediaId, "视频标题", "视频描述");
client.sendMediaMessageAsync("userid", Constants.CHAT_TYPE_INT_SINGLE,
        "video", mediaId, "视频标题", "视频描述");
```

图片、文件、语音可使用不带标题/描述的重载。视频标题最长 64 UTF-8 字节，描述最长 512 字节，SDK 超额拒绝。

### 下载并解密

```java
client.on(Constants.EVENT_MESSAGE_IMAGE, (frame, payload) -> {
    MediaContent image = ((MsgCallbackBody) payload).getImage();
    client.downloadFileAsync(image.getUrl(), image.getAesKey())
            .thenAccept(result -> {
                byte[] bytes = result.getData();
                String filename = result.getFilename();
                // 耗时文件写入应使用 thenAcceptAsync 并指定自己的 I/O 执行器。
            });
});
```

每个 URL 五分钟有效，aeskey 随该资源返回，不是 Bot Secret/EncodingAESKey。SDK 将 Base64 key 解码为 32 字节，IV 取前 16 字节，严格校验 PKCS#7 的 1..32 填充，不为损坏密文补零。

优先解析 Content-Disposition 文件名，回退 URL 路径；去除路径段和控制字符。默认最大下载密文 20MiB+32，流读取过程中也检查，不能通过缺 Content-Length 绕过。

静态同步下载兼容：

```java
MediaUtils.DownloadResult result = MediaUtils.downloadFile(url, aesKey);
byte[] fileData = result.getData();
String filename = result.getFilename();
```

异步客户端下载使用 Options 的连接/请求超时；静态工具使用 30s/60s 默认超时。实际写入示例见 Main.download：独立执行器、唯一文件名、受控目录。

### 图文混排接收

```java
client.on(Constants.EVENT_MESSAGE_MIXED, (frame, payload) -> {
    MixedContent mixed = ((MsgCallbackBody) payload).getMixed();
    for (MixedItem item : mixed.getItems()) {
        if (Constants.MSG_TYPE_TEXT.equals(item.getMsgType())) {
            String text = item.getText().getContent();
        } else if (Constants.MSG_TYPE_IMAGE.equals(item.getMsgType())) {
            MediaContent image = item.getImage();
            // 顺序/有界下载，不同时提交无限多媒体任务。
        }
    }
});
```

官方字段为 `mixed.msg_item`（参见[官方 SDK 消息定义](https://github.com/WecomTeam/aibot-node-sdk/blob/80615b987ef69c6028ad764924609247c0725955/src/types/message.ts#L71)）；Java `getItems()` 保留，旧输入 `items` 作为别名兼容。`QuoteContent` 的 mixed 也使用相同模型。

### 上传流程与重试

```text
init（upload_id） → chunk × N → finish（media_id/type/created_at）
```

单片 512KiB、最多 100 片、序号从 0 开始，MD5 校验完整内容。默认分片并发 2、额外重试 2 次；相同 upload_id/index/content 重传幂等。初始化后上传会话三十分钟有效。

同进程网络重连后，已成功片不重新调度，未确认片可继续；被替代、主动关闭、会话过期或预算耗尽会失败。进程重启不恢复任务。init/finish 的 UNKNOWN 不盲目重放。

上传恢复与回复上下文是两件事：网络重连后即使素材上传成功，旧回调 Frame 也会 STALE_CONTEXT，业务要根据当前会话资格选择后续通知方式。

## 事件列表

handler 形式为 `(Frame frame, Object payload)`；消息/事件回调的 frame 可用于对应回复，生命周期事件的 frame 为 null。

| 常量/事件名 | payload | 说明 |
| --- | --- | --- |
| EVENT_CONNECTED | null | 物理连接打开，尚未认证 |
| EVENT_AUTHENTICATED | null | 认证成功，READY |
| EVENT_DISCONNECTED | AiBotException | 连接断开原因 |
| EVENT_RECONNECTING | Integer | 重试次数 |
| EVENT_ERROR | Throwable | 协议/连接等错误 |
| EVENT_MESSAGE | MsgCallbackBody | 全部入站消息 |
| EVENT_MESSAGE_TEXT | MsgCallbackBody | 文本 |
| EVENT_MESSAGE_IMAGE | MsgCallbackBody | 图片 |
| EVENT_MESSAGE_MIXED | MsgCallbackBody | 图文混排 |
| EVENT_MESSAGE_VOICE | MsgCallbackBody | 已转写语音 |
| EVENT_MESSAGE_FILE | MsgCallbackBody | 文件 |
| EVENT_MESSAGE_VIDEO | MsgCallbackBody | 视频 |
| EVENT_EVENT | EventCallbackBody | 全部事件 |
| EVENT_ENTER_CHAT | EventCallbackBody | 当天首次进入单聊 |
| EVENT_TEMPLATE_CARD | EventCallbackBody | 卡片按钮/选项/菜单 |
| EVENT_FEEDBACK | EventCallbackBody | 关联反馈 |
| `event.disconnected_event` | EventCallbackBody | 服务端通知新连接替代旧连接，字面事件名 |

SDK 先触发通用 message/event，再触发类型事件；不要在两个 handler 里重复执行同一业务。未列出的类型仍保留原始 body，并按类型名分发。

未知字段可通过 `ProtocolModel.extensions()` 查看；原始数据始终在 `Frame.body`。旧 `EventInfo.getButtonKey()/getTaskId()` 兼容新结构，推荐直接读取 TemplateCardEvent/FeedbackEvent。

## 配置项

所有时间参数单位为毫秒；除了凭证，以下均可选。

| Options 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| botId | String | 无 | BotID |
| secret | String | 无 | 长连接专用密钥 |
| botIdFile | String | 无 | BotID 文件，优先于直接值 |
| secretFile | String | 无 | Secret 文件，优先于直接值 |
| wsUrl | String | wss://openws.work.weixin.qq.com | 端点；仅接受 ws/wss |
| connectTimeoutMs | long | 10000 | 建立连接超时 |
| requestTimeoutMs | long | 10000 | 认证、主动消息、上传 ACK；客户端下载读取超时 |
| replyAckTimeoutMs | long | 5000 | 被动回复、欢迎语、更新 ACK 超时 |
| heartbeatIntervalMs | long | 30000 | 应用 ping 间隔 |
| maxMissedHeartbeats | int | 3 | 连续缺有效 ACK 后断开 |
| reconnectBaseDelayMs | long | 1000 | 网络/认证退避基础值 |
| reconnectMaxDelayMs | long | 30000 | 退避上限，实际加入抖动 |
| maxReconnectAttempts | int | 10 | 网络重连预算，-1 无限、0 不重试 |
| maxAuthFailureAttempts | int | 5 | 独立认证重试预算，-1 无限、0 不重试 |
| maxReplyQueueSize | int | 32 | 同 req_id 在途+排队上限 |
| maxPendingRequests | int | 1024 | 全局请求容量；连接代流标记容量 |
| callbackThreads | int | 4 | 业务回调线程数 |
| callbackQueueSize | int | 256 | SDK 回调队列上限 |
| maxDownloadBytes | long | 20MiB+32 | 下载密文字节上限 |
| uploadChunkConcurrency | int | 2 | 全局分片 worker 数，允许 1..4 |
| maxChunkRetries | int | 2 | 分片额外重试次数 |

```java
Options options = new Options()
        .setBotIdFile("/etc/wecom/bot_id.txt")
        .setSecretFile("/etc/wecom/secret.txt")
        .setConnectTimeoutMs(10000)
        .setRequestTimeoutMs(10000)
        .setReplyAckTimeoutMs(5000)
        .setMaxReconnectAttempts(10)
        .setMaxAuthFailureAttempts(5)
        .setUploadChunkConcurrency(2);
```

时间/容量必须为正，重试参数不得小于 -1，reconnectMaxDelayMs 不得小于基础值。每连接代的流标记在对应二十四小时回调窗口内保留，过期清理；容量满明确失败。媒体任务总数最多 4。限流表最多 10000 个活跃目标，过期清理，满表明确失败。

## 错误处理、限流与线程安全

### ACK 与结果未知

异步发送成功返回服务端 Frame。只有明确整数 errcode=0 的 ACK 才成功；缺失/无效 ACK 不按默认零值认定成功。

| 错误码 | 调用方含义 |
| --- | --- |
| NOT_READY | 未认证或正在恢复 |
| CLOSED / SUPERSEDED / RETRY_EXHAUSTED | 客户端终态，上层处理配置/owner 后重建 |
| SERVER_REJECTED | 服务端明确拒绝，getErrCode() 获取服务端码 |
| PROTOCOL_ERROR | 响应或协议结构错误 |
| UNKNOWN | 服务端可能已经执行，不能盲目重试 |
| SEND_FAILED | WebSocket 明确拒绝入队 |
| QUEUE_FULL | 请求、回复队列、媒体或状态跟踪容量已满 |
| RATE_LIMITED | 本地额度耗尽，可读取 retryAfterMs |
| STALE_CONTEXT | Frame 属于其他客户端或旧连接代 |
| DEADLINE_EXCEEDED | 回复/流/事件窗口过期 |
| INVALID_ARGUMENT | 类型、字段、格式或 UTF-8 字节数不合法 |

SDK 不透传可能含敏感信息的服务器 errmsg 到异常正文。同 req_id 一旦 UNKNOWN，本连接代封闭该队列，剩余等待者也失败；不能用迟到 ACK 完成下一帧。

### 错误分类示例

将下面方法放入业务类，在 whenComplete 中调用：

```java
private static void handleFailure(Throwable error) {
    Throwable cause = error;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
            && cause.getCause() != null) {
        cause = cause.getCause();
    }
    if (cause instanceof RateLimitException) {
        long waitMs = ((RateLimitException) cause).getRetryAfterMs();
        System.err.println("额度耗尽，最早可重试时间差：" + waitMs + "ms");
    } else if (cause instanceof AiBotException) {
        AiBotException sdkError = (AiBotException) cause;
        System.err.println("SDK code=" + sdkError.getCode() + ", errcode=" + sdkError.getErrCode());
        // UNKNOWN 保存准确状态，不在这里自动再次发送。
    } else {
        System.err.println("操作失败：" + cause.getClass().getSimpleName());
    }
}
```

需要 `java.util.concurrent.CompletionException/ExecutionException` 与 SDK 异常类型的 imports。完整可运行处理见 Main.failure。

取消返回的 future 仅取消等待，不能证明服务端没有执行，也不会自动重放/回滚已发送内容。

### 本地限流策略

回复与主动推送按同一目标 id 共用滚动额度：30 次/60 秒、1000 次/小时。切换 chat_type 不绕过同 id 限流。默认保守计入流刷新，服务端具体计数仍需真实联调确认。

上传额度单独按机器人计算，30 次/分钟、1000 次/小时，本地以初始化为一次尝试。**超额立即失败**，SDK 不等待、不合并快照、不自动补发。

额度与期限在实际准备发包时检查；入队成功不保证最终成功。调用方同时管理刷新节奏、终帧预算和小时额度。普通被动回复二十四小时，欢迎语/点击更新五秒，单条流十分钟。

### 执行线程

- 网络/生命周期只做解析、ACK、控制逻辑，不执行用户 handler。
- handler 在独立有界回调池，单个 handler 的异常不影响其他 handler。
- 模型、工具、文件写入使用调用方执行器；示例业务池与 SDK 回调池独立。
- CompletionStage 的阻塞回调使用 `thenApplyAsync/thenAcceptAsync` 并指定自己的执行器。
- 回调队列满不在网络线程兜底执行，记录错误并增加 `getRejectedCallbackCount()`；应用监控、排重与恢复。

## 同步接口兼容

旧同步签名保留并标注 Deprecated，推荐新代码使用对应异步入口。StreamSession.update/finish 和 MediaUtils 静态工具仍可用于业务线程。

| 旧接口 | 推荐入口 |
| --- | --- |
| reply / replyText / replyMarkdown | replyAsync / replyTextAsync / replyMarkdownAsync |
| replyStream | replyStreamAsync 或 StreamSession |
| replyTemplateCard / replyWelcome | 对应 Async |
| updateTemplateCard | updateTemplateCardAsync，可带 userIds |
| sendMessage / sendMarkdown | sendMessageAsync / sendMarkdownAsync |
| replyMedia / sendMediaMessage | 对应 Async |
| send(cmd, body) | sendAsync(cmd, body)，仅支持高级业务/媒体命令 |
| MediaUtils.uploadFile | uploadMediaAsync(type, Path) |
| disconnect | close（disconnect 继续可用） |

同步上传/下载示例：

```java
String mediaId = MediaUtils.uploadFile(client, "image", "/path/to/picture.png");
client.replyMedia(frame, "image", mediaId);

MediaUtils.DownloadResult result = MediaUtils.downloadFile(url, aesKey);
```

兼容行为变化：

1. 回复从 fire-and-forget 改为等待 ACK；原 void 方法通过 IOException/AiBotException 报错。
2. send/uploadFile 的 UNKNOWN 映射为 TimeoutException；异步接口使用稳定 SDK 错误。
3. replyText 编码为结束 stream，replyMarkdown 保留合法 Markdown 协议。
4. 必须使用本客户端真实接收的 Frame；不能手工构造回调或跨代复用。
5. 高级 send 不用于手工认证、ping 或生成新 req_id 的被动回复。
6. close/disconnect 后不能 restart，需要新建客户端。

## Spring Boot/WebFlux 与 agent-platform

SDK 没有 Spring、Redis、AgentScope 依赖。Spring 应用管理客户端生命周期，并把异步结果转为 Mono：

```java
Mono<Frame> reply = Mono.defer(() -> Mono.fromCompletionStage(
        client.replyMarkdownAsync(frame, "来自 WebFlux 的回复")));
```

`Mono.defer` 让请求在订阅时提交；订阅取消仍不能证明服务端未执行。真实 Agent 入口还需可信用户映射、授权、msgid 排重和稳定 threadId；SDK 不替代这些业务。

### OkHttp 依赖管理

AgentScope OpenAI 2.0.1 带入 okhttp-jvm 5.3.2，SDK 默认 okhttp 4.12.0。平台消费须把两项统一管理，避免两套同包实现共存。追加到消费项目**现有** dependencyManagement：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>okhttp</artifactId>
            <version>5.3.2</version>
        </dependency>
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>okhttp-jvm</artifactId>
            <version>5.3.2</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

[消费夹具](src/test/consumer-smoke/pom.xml) 继承当前 agent-platform parent，在单一 OkHttp 5 实现下验证 Boot/AgentScope/Reactor 与实际本地 WebSocket 认证、回调、ACK。SDK 安装后用 JDK 21 执行：

```bash
mvn -f src/test/consumer-smoke/pom.xml verify
```

### 集群与身份

一个 bot 只有一个有效连接，集群使用 bot 连接租约/主备切换；被替代后 SDK 停止抢连。Agent 会话运行租约与 bot 连接租约是两种职责。

企微 userid 可能是企业主体加密标识，原样保留，平台按企业身份可信转换/映射，不能直接视为工号/平台 userId。msgid 排重、req_id 关联、stream.id、Agent threadId/runId 分别管理。

## 测试与常见问题

### 自动化测试

```bash
mvn clean verify
```

JUnit 4 + 本地 MockWebServer，不需要真实凭证；环境须允许 loopback 端口绑定。覆盖协议 JSON、全部 1..32 填充、ACK/晚 ACK、代切换、关闭竞态、流排序、认证预算、心跳黑洞、限流与上传重连续传。

真实企微仅在提供独立测试机器人环境变量后显式执行：

```bash
mvn -Plive-bot verify
```

该 profile 验证认证/关闭，不自动发送业务消息；完整消息/卡片/媒体人工验收步骤见 [验证记录](docs/verification.md)。构建通过不等同于真实服务端联调通过。

### 为什么 SDK 有异步接口，还需要自己的业务池？

异步发送解决网络等待，handler、链式回调里自己的模型/工具/文件操作仍可能阻塞。示例把长任务放到 business 池，五秒事件响应直接非阻塞提交。

### 为何超时后不继续下一帧？

同回调的流刷新共用 req_id，没有服务端逐帧序号。继续发送会使上一帧迟到 ACK 被误归给下一帧，所以该 lane UNKNOWN 后停止。

### 可以用控制帧 ping 代替 SDK 的 JSON ping 吗？

不能只依赖控制帧；企微协议要求应用层 ping 请求及 ACK，SDK 自动处理。

### 为什么某些已结束流或卡片还有记录？

SDK 保留流标记防止在回调窗口内复用旧流；示例保留演示卡片防止重复交互。它们有容量限制；业务持久化和跨进程恢复由应用管理。

### 许可证

原社区 README 标称 MIT，但原仓库未附 LICENSE。本次保持来源说明；向外发布前核对上游许可。当前产物用于内部构建，没有发布到远端公共仓库。
