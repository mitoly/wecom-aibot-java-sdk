package com.wecom.aibot.example;

import com.wecom.aibot.*;
import com.wecom.aibot.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 企业微信智能机器人完整演示。
 * <p>运行前设置 WECHAT_BOT_ID/WECHAT_BOT_SECRET，或对应的 _FILE 凭证路径。
 * <p>发送 /help 查看命令；--help 仅输出本机使用说明，不需要凭证、不建立连接。
 * <p>媒体发送只读取本机环境变量指定的演示素材，不能通过聊天消息指定任意本机路径。
 */
public final class Main implements AutoCloseable {
    private static final String HELP = "# Java SDK 演示\n"
            + "- 普通文本：一次性文本回复\n"
            + "- `/stream 问题`：累计正文、间隔刷新、终帧 ACK\n"
            + "- `/markdown`：Markdown 格式与反馈\n"
            + "- `/card text_notice`：文本通知卡片\n"
            + "- `/card news_notice`：图文展示卡片\n"
            + "- `/card button_interaction`：按钮交互卡片\n"
            + "- `/card vote_interaction`：投票选择卡片\n"
            + "- `/card multiple_interaction`：多项选择卡片\n"
            + "- `/push 内容`：主动推送到当前会话\n"
            + "- `/file`、`/image`、`/voice`、`/video`：上传本机配置的演示素材并回复\n"
            + "- 直接发送图片/文件/视频：下载、解密并保存到 downloads\n"
            + "- 直接发送语音：回复已经转写的文本\n"
            + "- 图文混排：按顺序处理文本与图片\n"
            + "卡片和文件都是 SDK 演示，不会调用平台工具或执行真实审批。";

    private final ConsoleLogger log = new ConsoleLogger();
    private final WeComAiBotClient client;
    private final ThreadPoolExecutor business;
    private final ConcurrentMap<String, CardContext> cards = new ConcurrentHashMap<>();
    private final Path downloadDirectory;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Main(Options options) throws IOException {
        client = new WeComAiBotClient(options, log);
        // 耗时示例使用自己的执行器，SDK 回调不等待模拟模型或本机文件写入。
        business = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), runnable -> {
                    Thread thread = new Thread(runnable, "wecom-example-business");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        downloadDirectory = Paths.get(environment("WECHAT_DEMO_DOWNLOAD_DIR", "downloads"));
        registerLifecycle();
        registerMessages();
        registerEvents();
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            if (args.length == 1 && "--help".equals(args[0])) {
                printUsage();
                return;
            }
            throw new IllegalArgumentException("仅支持 --help 参数；凭证请通过环境变量或文件提供");
        }

        Options options = new Options()
                .setBotId(System.getenv("WECHAT_BOT_ID"))
                .setSecret(System.getenv("WECHAT_BOT_SECRET"));
        String botFile = System.getenv("WECHAT_BOT_ID_FILE");
        String secretFile = System.getenv("WECHAT_BOT_SECRET_FILE");
        if (botFile != null && !botFile.trim().isEmpty()) {
            options.setBotIdFile(botFile);
        }
        if (secretFile != null && !secretFile.trim().isEmpty()) {
            options.setSecretFile(secretFile);
        }
        String wsUrl = System.getenv("WECHAT_BOT_WS_URL");
        if (wsUrl != null && !wsUrl.trim().isEmpty()) {
            options.setWsUrl(wsUrl);
        }

        try (Main demo = new Main(options)) {
            Thread shutdown = new Thread(demo::close, "wecom-example-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                demo.log.info("正在启动企业微信智能机器人，连接成功后发送 /help 查看命令");
                demo.client.run();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // JVM 已进入退出流程，关闭钩子负责幂等清理。
                }
            }
        }
    }

    private void registerLifecycle() {
        client.on(Constants.EVENT_CONNECTED, (frame, payload) -> log.info("==> WebSocket 已建立，正在认证"));
        client.on(Constants.EVENT_AUTHENTICATED, (frame, payload) -> log.info("==> 认证成功，状态={}", client.getState()));
        client.on(Constants.EVENT_DISCONNECTED, (frame, payload) -> log.info("==> 连接断开，状态={}", client.getState()));
        client.on(Constants.EVENT_RECONNECTING, (frame, payload) -> log.info("==> 正在重连，第 {} 次", payload));
        client.on(Constants.EVENT_ERROR, (frame, payload) -> {
            if (payload instanceof Throwable) {
                failure("SDK", (Throwable) payload);
            }
        });
        client.on("event.disconnected_event", (frame, payload) ->
                log.warn("==> 机器人已被新连接替代，当前实例停止自重连"));
    }

    private void registerMessages() {
        client.on(Constants.EVENT_MESSAGE, (frame, payload) -> {
            MsgCallbackBody message = (MsgCallbackBody) payload;
            log.info("==> 收到 {} 消息，msgid={}", message.getMsgType(), message.getMsgId());
        });
        client.on(Constants.EVENT_MESSAGE_TEXT, (frame, payload) -> execute(() -> handleText(frame, (MsgCallbackBody) payload)));
        client.on(Constants.EVENT_MESSAGE_VOICE, (frame, payload) -> {
            MsgCallbackBody message = (MsgCallbackBody) payload;
            if (message.getVoice() != null) {
                observe("语音转写回复", client.replyTextAsync(frame, "语音转写结果：" + message.getVoice().getContent()));
            }
        });
        client.on(Constants.EVENT_MESSAGE_IMAGE, (frame, payload) -> receiveMedia(frame, ((MsgCallbackBody) payload).getImage(), "图片"));
        client.on(Constants.EVENT_MESSAGE_FILE, (frame, payload) -> receiveMedia(frame, ((MsgCallbackBody) payload).getFile(), "文件"));
        client.on(Constants.EVENT_MESSAGE_VIDEO, (frame, payload) -> receiveMedia(frame, ((MsgCallbackBody) payload).getVideo(), "视频"));
        client.on(Constants.EVENT_MESSAGE_MIXED, (frame, payload) -> receiveMixed(frame, (MsgCallbackBody) payload));
    }

    private void registerEvents() {
        // 五秒窗口内的事件响应不排入耗时业务队列，也不执行模拟模型。
        client.on(Constants.EVENT_ENTER_CHAT, (frame, payload) -> {
            ReplyBody welcome = new ReplyBody();
            welcome.setMsgType(Constants.MSG_TYPE_TEXT);
            welcome.setText(new TextContent("你好！我是 Java SDK 演示机器人。发送 /help 查看示例命令。"));
            observe("欢迎语", client.replyWelcomeAsync(frame, welcome));
        });
        client.on(Constants.EVENT_TEMPLATE_CARD, (frame, payload) -> handleCard(frame, (EventCallbackBody) payload));
        client.on(Constants.EVENT_FEEDBACK, (frame, payload) -> {
            FeedbackEvent feedback = ((EventCallbackBody) payload).getEvent().getFeedbackEvent();
            if (feedback != null) {
                // 不记录反馈正文，不向反馈事件发送普通回复。
                log.info("==> 收到反馈，id={}，type={}，原因={}", feedback.getId(), feedback.getType(), feedback.getInaccurateReasonList());
            }
        });
    }

    private void handleText(Frame frame, MsgCallbackBody message) throws Exception {
        String text = message.getText() == null ? "" : message.getText().getContent();
        text = text == null ? "" : text.trim();
        // 群聊中通常有简单的开头 @机器人称呼。
        String command = text.replaceFirst("^@\\S+\\s+", "");
        if ("/help".equals(command)) {
            await("帮助", client.replyMarkdownAsync(frame, HELP));
        } else if (command.equals("/stream") || command.startsWith("/stream ")) {
            stream(frame, command.length() > 7 ? command.substring(8).trim() : "演示问题");
        } else if ("/markdown".equals(command)) {
            ReplyBody reply = new ReplyBody();
            reply.setMsgType(Constants.MSG_TYPE_MARKDOWN);
            MarkdownContent markdown = new MarkdownContent("# Markdown 示例\n**加粗**、*斜体*、`行内代码`\n\n| 项目 | 状态 |\n| :--- | :--- |\n| ACK | 已等待 |\n| 文本 | UTF-8 |\n\n可对这条回复进行反馈。");
            ReplyFeedback feedback = new ReplyFeedback();
            feedback.setId(WeComAiBotClient.generateReqId("feedback"));
            markdown.setFeedback(feedback);
            reply.setMarkdown(markdown);
            await("Markdown", client.replyAsync(frame, reply));
        } else if (command.equals("/card") || command.startsWith("/card ")) {
            String type = command.length() > 5 ? command.substring(6).trim() : "button_interaction";
            sendCard(frame, message, type);
        } else if (command.startsWith("/push ")) {
            boolean group = Constants.CHAT_TYPE_GROUP.equals(message.getChatType());
            String target = group ? message.getChatId() : message.getFrom().getUserId();
            await("主动推送", client.sendMarkdownAsync(target,
                    group ? Constants.CHAT_TYPE_INT_GROUP : Constants.CHAT_TYPE_INT_SINGLE,
                    "**主动推送示例**\n" + command.substring(6)));
        } else if (Arrays.asList("/file", "/image", "/voice", "/video").contains(command)) {
            upload(frame, command.substring(1));
        } else {
            await("文本", client.replyTextAsync(frame, "收到：" + text));
        }
    }

    private void stream(Frame frame, String question) throws Exception {
        StreamSession stream = client.newStream(frame);
        String content = "正在思考：" + question;
        await("流式首帧", stream.updateAsync(content));
        // 模拟耗时处理；只在示例自己的业务线程休眠，不阻塞 SDK 收帧/ACK/事件响应。
        TimeUnit.SECONDS.sleep(3);
        content += "\n\n第一步完成：已经收到问题。";
        await("流式更新", stream.updateAsync(content));
        TimeUnit.SECONDS.sleep(3);
        content += "\n第二步完成：这是一条累计正文示例，不包含真实模型调用。";
        await("流式终帧", stream.finishAsync(content));
        log.info("==> stream={}，状态={}，耗时={}ms", stream.getId(), stream.getState(), stream.getElapsedMs());
    }

    private void sendCard(Frame frame, MsgCallbackBody message, String type) throws Exception {
        String taskId = WeComAiBotClient.generateReqId("task");
        TemplateCard card;
        try {
            card = ExampleCards.create(type, taskId);
        } catch (IllegalArgumentException error) {
            await("卡片类型提示", client.replyTextAsync(frame, error.getMessage()));
            return;
        }
        CardContext context = new CardContext(type, message.getFrom().getUserId(), conversation(message.getChatType(), message.getChatId(), message.getFrom().getUserId()));
        boolean registered;
        synchronized (cards) {
            cards.entrySet().removeIf(entry -> System.nanoTime() - entry.getValue().created >= TimeUnit.HOURS.toNanos(24));
            registered = cards.size() < 256;
            if (registered) {
                cards.put(taskId, context);
            }
        }
        if (!registered) {
            await("卡片容量提示", client.replyTextAsync(frame, "演示卡片容量已满，请稍后再试。"));
            return;
        }
        try {
            await("模板卡片", client.replyTemplateCardAsync(frame, card));
        } catch (Exception error) {
            // UNKNOWN 时保留记录：卡片可能已经送达，后续真实点击仍可匹配。
            Throwable cause = unwrap(error);
            if (!(cause instanceof AiBotException) || ((AiBotException) cause).getCode() != AiBotException.Code.UNKNOWN) {
                cards.remove(taskId, context);
            }
            throw error;
        }
    }

    private void handleCard(Frame frame, EventCallbackBody event) {
        TemplateCardEvent detail = event.getEvent().getTemplateCardEvent();
        if (detail == null || event.getFrom() == null) {
            log.warn("卡片事件缺少详情或操作者");
            return;
        }
        CardContext context = cards.get(detail.getTaskId());
        String userId = event.getFrom().getUserId();
        if (context == null || System.nanoTime() - context.created >= TimeUnit.HOURS.toNanos(24)
                || !context.userId.equals(userId)
                || !context.conversation.equals(conversation(event.getChatType(), event.getChatId(), userId))) {
            log.warn("卡片未登记、已过期或操作者不匹配；不执行更新");
            return;
        }
        String result = context.processed.compareAndSet(false, true) ? "已收到操作：" + detail.getEventKey() : "该演示交互已经处理";
        if (detail.getSelectedItems() != null && detail.getSelectedItems().getSelectedItem() != null) {
            result += "；提交 " + detail.getSelectedItems().getSelectedItem().size() + " 组选项";
            for (SelectedItem selection : detail.getSelectedItems().getSelectedItem()) {
                List<String> ids = selection.getOptionIds() == null ? Collections.emptyList() : selection.getOptionIds().getOptionId();
                log.info("==> 选择框={}，option_ids={}", selection.getQuestionKey(), ids);
            }
        }
        TemplateCard updated = ExampleCards.processed(context.type, detail.getTaskId(), result, detail.getSelectedItems());
        // 使用此次点击的 frame/req_id 和原 task_id；只更新当前操作者的卡片视图。
        observe("更新卡片", client.updateTemplateCardAsync(frame, updated, Collections.singletonList(userId)));
    }

    private void upload(Frame frame, String type) throws Exception {
        String variable = "WECHAT_DEMO_UPLOAD_" + type.toUpperCase(Locale.ROOT);
        String path = System.getenv(variable);
        if (path == null || path.trim().isEmpty()) {
            await("媒体配置提示", client.replyTextAsync(frame, "请在机器人本机设置 " + variable + "，指向一份可公开分享的演示素材。"));
            return;
        }
        UploadedMedia uploaded = client.uploadMediaAsync(type, Paths.get(path)).toCompletableFuture().get();
        await("媒体回复", client.replyMediaAsync(frame, type, uploaded.getMediaId(),
                "video".equals(type) ? "SDK 视频示例" : null,
                "video".equals(type) ? "本机配置的演示素材" : null));
        log.info("==> 上传完成，type={}，created_at={}", uploaded.getType(), uploaded.getCreatedAt());
    }

    private void receiveMedia(Frame frame, MediaContent reference, String label) {
        if (reference == null) {
            return;
        }
        observe(label + "下载与回复", download(reference)
                .thenCompose(path -> client.replyMarkdownAsync(frame, "已下载并保存" + label + "：`" + path.getFileName() + "`")));
    }

    private void receiveMixed(Frame frame, MsgCallbackBody message) {
        if (message.getMixed() == null || message.getMixed().getItems() == null) {
            observe("图文提示", client.replyTextAsync(frame, "图文消息没有可处理的内容。"));
            return;
        }
        CompletionStage<String> result = CompletableFuture.completedFuture("# 图文消息处理\n");
        // 顺序处理，避免一条混排消息同时占满 SDK 的四个媒体任务槽。
        for (MixedItem item : message.getMixed().getItems()) {
            if (Constants.MSG_TYPE_TEXT.equals(item.getMsgType()) && item.getText() != null) {
                String content = item.getText().getContent();
                result = result.thenApply(summary -> summary + "- 文本：" + content + "\n");
            } else if (Constants.MSG_TYPE_IMAGE.equals(item.getMsgType()) && item.getImage() != null) {
                MediaContent reference = item.getImage();
                result = result.thenCompose(summary -> download(reference)
                        .thenApply(path -> summary + "- 图片已保存：`" + path.getFileName() + "`\n"));
            }
        }
        observe("图文回复", result.thenCompose(summary -> client.replyMarkdownAsync(frame, summary)));
    }

    private CompletionStage<Path> download(MediaContent reference) {
        return client.downloadFileAsync(reference.getUrl(), reference.getAesKey()).thenApplyAsync(result -> {
            try {
                Files.createDirectories(downloadDirectory);
                String suffix = result.getFilename().replaceAll("[^\\p{L}\\p{N}._-]", "_");
                suffix = suffix.substring(0, Math.min(64, suffix.length()));
                Path target = Files.createTempFile(downloadDirectory, "received-", "-" + suffix);
                Files.write(target, result.getData());
                log.info("==> 已保存媒体，大小={} 字节", result.getData().length);
                return target;
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        }, business);
    }

    private void execute(CheckedTask task) {
        try {
            business.execute(() -> {
                try {
                    task.run();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (Exception error) {
                    failure("业务示例", error);
                }
            });
        } catch (RejectedExecutionException error) {
            log.warn("演示业务队列已满或程序正在退出");
        }
    }

    private void observe(String operation, CompletionStage<?> stage) {
        stage.whenComplete((ack, error) -> {
            if (error == null) {
                log.info("==> {}已确认", operation);
            } else {
                failure(operation, error);
            }
        });
    }

    private void await(String operation, CompletionStage<?> stage) throws Exception {
        stage.toCompletableFuture().get();
        log.info("==> {}已确认", operation);
    }

    private void failure(String operation, Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof RateLimitException) {
            log.warn("{}限流，retryAfter={}ms；示例不自动补发", operation, ((RateLimitException) cause).getRetryAfterMs());
        } else if (cause instanceof AiBotException) {
            AiBotException failure = (AiBotException) cause;
            log.warn("{}失败，code={}，errcode={}", operation, failure.getCode(), failure.getErrCode());
        } else {
            log.warn("{}失败，异常类型={}", operation, cause.getClass().getSimpleName());
        }
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof CompletionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static String conversation(String type, String chatId, String userId) {
        return Constants.CHAT_TYPE_GROUP.equals(type) ? "group:" + chatId : "single:" + userId;
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    private static void printUsage() {
        System.out.println("企业微信 Java SDK 演示机器人（Java 8+）");
        System.out.println("凭证：WECHAT_BOT_ID / WECHAT_BOT_SECRET，或 WECHAT_BOT_ID_FILE / WECHAT_BOT_SECRET_FILE");
        System.out.println("可选：WECHAT_BOT_WS_URL、WECHAT_DEMO_DOWNLOAD_DIR（默认 downloads）");
        System.out.println("媒体：WECHAT_DEMO_UPLOAD_FILE / IMAGE / VOICE / VIDEO，指向本机演示素材");
        System.out.println("运行：mvn compile exec:java；只查看帮助：mvn compile exec:java -Dexec.args=--help");
        System.out.println(HELP);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            log.info("正在关闭演示机器人...");
            client.close();
            business.shutdownNow();
            cards.clear();
        }
    }

    private interface CheckedTask {
        void run() throws Exception;
    }

    private static final class CardContext {
        final String type;
        final String userId;
        final String conversation;
        final long created = System.nanoTime();
        final AtomicBoolean processed = new AtomicBoolean();

        CardContext(String type, String userId, String conversation) {
            this.type = type;
            this.userId = userId;
            this.conversation = conversation;
        }
    }
}
