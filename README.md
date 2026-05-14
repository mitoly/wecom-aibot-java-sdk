# 企业微信智能机器人 Java SDK

企业微信智能机器人 WebSocket 长连接 Java SDK，基于 [Go SDK](https://github.com/evinvie/wecom-aibot-go-sdk) 移植而来。

## 功能特性

- ✅ WebSocket 长连接管理（自动重连、指数退避）
- ✅ 身份认证（aibot_subscribe）
- ✅ 心跳保活（fire-and-forget）
- ✅ 消息/事件分发（线程安全事件总线）
- ✅ 流式消息支持（自动追踪 10 分钟超时限制）
- ✅ 媒体文件下载与 AES-256-CBC 解密
- ✅ 分片上传素材文件
- ✅ 模板卡片消息
- ✅ 凭证安全（文件读取、日志脱敏）

## 快速开始

### 环境要求

- Java 8+
- Maven 3.6+

### 添加依赖

```xml
<dependency>
    <groupId>com.wecom</groupId>
    <artifactId>wecom-aibot-java-sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 基本使用

```java
import com.wecom.aibot.*;
import com.wecom.aibot.model.*;

public class MyBot {
    public static void main(String[] args) throws Exception {
        // 创建客户端
        Options options = new Options()
                .setBotId("your-bot-id")
                .setSecret("your-secret");

        WeComAiBotClient client = new WeComAiBotClient(options);

        // 监听文本消息
        client.on(Constants.EVENT_MESSAGE_TEXT, (frame, payload) -> {
            MsgCallbackBody msg = (MsgCallbackBody) payload;
            try {
                client.replyText(frame, "收到: " + msg.getText().getContent());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // 启动（阻塞）
        client.run();
    }
}
```

### 从文件读取凭证（推荐）

```java
Options options = new Options()
        .setBotIdFile("/etc/wecom/bot_id.txt")
        .setSecretFile("/etc/wecom/secret.txt");
```

### 流式回复

```java
client.on(Constants.EVENT_MESSAGE_TEXT, (frame, payload) -> {
    try {
        StreamSession stream = client.newStream(frame);
        stream.update("正在处理...");
        // ... 执行耗时操作 ...
        stream.finish("处理完成！最终结果是...");
    } catch (Exception e) {
        e.printStackTrace();
    }
});
```

### 上传媒体文件

```java
String mediaId = MediaUtils.uploadFile(client, "image", "/path/to/image.png");
```

### 下载并解密媒体文件

```java
MediaUtils.DownloadResult result = MediaUtils.downloadFile(url, aesKey);
byte[] fileData = result.getData();
String filename = result.getFilename();
```

## 事件列表

| 事件常量 | 说明 |
|---------|------|
| `EVENT_CONNECTED` | 连接建立 |
| `EVENT_AUTHENTICATED` | 认证成功 |
| `EVENT_DISCONNECTED` | 连接断开 |
| `EVENT_RECONNECTING` | 正在重连 |
| `EVENT_ERROR` | 发生错误 |
| `EVENT_MESSAGE` | 所有消息 |
| `EVENT_MESSAGE_TEXT` | 文本消息 |
| `EVENT_MESSAGE_IMAGE` | 图片消息 |
| `EVENT_MESSAGE_MIXED` | 图文混排消息 |
| `EVENT_MESSAGE_VOICE` | 语音消息 |
| `EVENT_MESSAGE_FILE` | 文件消息 |
| `EVENT_MESSAGE_VIDEO` | 视频消息 |
| `EVENT_EVENT` | 所有事件 |
| `EVENT_ENTER_CHAT` | 用户进入会话 |
| `EVENT_TEMPLATE_CARD` | 模板卡片点击 |
| `EVENT_FEEDBACK` | 用户反馈 |

## 配置项

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `wsUrl` | `wss://openws.work.weixin.qq.com` | WebSocket 端点 |
| `heartbeatIntervalMs` | 30000 | 心跳间隔（毫秒） |
| `reconnectBaseDelayMs` | 1000 | 重连初始延迟（毫秒） |
| `reconnectMaxDelayMs` | 30000 | 重连最大延迟（毫秒） |
| `maxReconnectAttempts` | 10 | 最大重连次数（-1=无限） |
| `requestTimeoutMs` | 10000 | 请求超时（毫秒） |

## 安全最佳实践

### 凭证管理

1. **永远不要在代码中硬编码 Secret！**
2. 推荐使用环境变量或文件读取方式：

```java
// 方式一：环境变量
Options options = new Options()
        .setBotId(System.getenv("WECHAT_BOT_ID"))
        .setSecret(System.getenv("WECHAT_BOT_SECRET"));

// 方式二：文件读取（推荐生产环境）
Options options = new Options()
        .setBotIdFile("/etc/wecom/bot_id.txt")
        .setSecretFile("/etc/wecom/secret.txt");
```

3. 凭证文件权限建议设为 `600`（仅 owner 可读写）
4. 日志中的 Secret 已自动脱敏（仅显示前4位和后4位）

### 线程安全

- 客户端所有公共方法都是线程安全的
- 事件 handler 在独立线程中执行，不阻塞消息接收
- 单个 handler 抛出异常不影响其他 handler

## 技术实现

本 SDK 基于 Go SDK 移植，保留了所有已修复的 Bug 防护：

| Bug | 修复方案 |
|-----|---------|
| disconnected_event 空指针 | `closeConn()` 幂等设计 + `closedByServer` 标记 |
| Send() TOCTOU 竞态 | `synchronized(writeLock)` 原子检查+写入 |
| Event handler 注销错乱 | `AtomicLong` 唯一 ID + `AtomicBoolean` 幂等 dispose |
| 心跳阻塞业务 | `sendPing()` fire-and-forget，不走 pending 机制 |
| 重连 attempt 不归零 | 连接持续 > 1 分钟后断开时重置计数器 |
| AES 解密不安全 | key 长度校验 + 密文长度校验 + PKCS7 一致性验证 + 副本解密 |

## 依赖

- [OkHttp 4.12.0](https://square.github.io/okhttp/) — WebSocket 客户端
- [Jackson 2.17.0](https://github.com/FasterXML/jackson) — JSON 序列化
- [SLF4J 2.0.12](https://www.slf4j.org/) — 日志门面

## License

MIT
