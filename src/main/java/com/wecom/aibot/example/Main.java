package com.wecom.aibot.example;

import com.wecom.aibot.*;
import com.wecom.aibot.model.*;

/**
 * 企业微信智能机器人 Java SDK 使用示例。
 * <p>
 * 运行前请设置环境变量：
 * <ul>
 *   <li>WECHAT_BOT_ID — 机器人 ID</li>
 *   <li>WECHAT_BOT_SECRET — 机器人密钥</li>
 * </ul>
 * <p>
 * 或者通过凭证文件方式（更安全）配置。
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // 推荐方式一：从环境变量读取（适用于容器/CI 环境）
        String botId = System.getenv("WECHAT_BOT_ID");
        String secret = System.getenv("WECHAT_BOT_SECRET");

        if (botId == null || botId.isEmpty() || secret == null || secret.isEmpty()) {
            System.err.println("请设置环境变量 WECHAT_BOT_ID 和 WECHAT_BOT_SECRET");
            System.err.println("或使用 Options.setBotIdFile() / Options.setSecretFile() 从文件读取");
            System.exit(1);
        }

        // 创建客户端
        Options options = new Options()
                .setBotId(botId)
                .setSecret(secret);

        WeComAiBotClient client = new WeComAiBotClient(options);

        // 监听认证成功事件
        client.on(Constants.EVENT_AUTHENTICATED, (frame, payload) -> {
            System.out.println("==> 认证成功！");
        });

        // 监听连接断开事件
        client.on(Constants.EVENT_DISCONNECTED, (frame, payload) -> {
            System.out.println("==> 连接断开");
        });

        // 监听重连事件
        client.on(Constants.EVENT_RECONNECTING, (frame, payload) -> {
            System.out.println("==> 正在重连 (第 " + payload + " 次)");
        });

        // 处理文本消息 —— 流式回复示例
        client.on(Constants.EVENT_MESSAGE_TEXT, (frame, payload) -> {
            MsgCallbackBody msg = (MsgCallbackBody) payload;
            String content = msg.getText().getContent();
            String streamId = WeComAiBotClient.generateReqId("stream");

            try {
                client.replyStream(frame, streamId, "正在思考中...", false);
                client.replyStream(frame, streamId, "你说的是: \"" + content + "\"", true);
            } catch (Exception e) {
                System.err.println("回复失败: " + e.getMessage());
            }
        });

        // 用户进入会话时发送欢迎语
        client.on(Constants.EVENT_ENTER_CHAT, (frame, payload) -> {
            ReplyBody welcomeBody = new ReplyBody();
            welcomeBody.setMsgType(Constants.MSG_TYPE_TEXT);
            welcomeBody.setText(new TextContent("你好！有什么可以帮你的吗？"));
            try {
                client.replyWelcome(frame, welcomeBody);
            } catch (Exception e) {
                System.err.println("发送欢迎语失败: " + e.getMessage());
            }
        });

        // 注册 JVM 关闭钩子实现优雅退出
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭...");
            client.disconnect();
        }));

        // 启动客户端（阻塞直到断开或超过最大重连次数）
        System.out.println("正在启动企业微信智能机器人...");
        client.run();
    }
}
