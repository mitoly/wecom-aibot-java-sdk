package com.wecom.aibot;

/**
 * SDK 常量定义：WebSocket 帧命令类型和事件名称。
 */
public final class Constants {

    private Constants() {
    }

    // =========================================================================
    // WebSocket 帧命令类型常量
    // =========================================================================

    public static final String CMD_SUBSCRIBE = "aibot_subscribe";
    public static final String CMD_PING = "ping";
    public static final String CMD_MSG_CALLBACK = "aibot_msg_callback";
    public static final String CMD_EVENT_CALLBACK = "aibot_event_callback";
    public static final String CMD_RESPOND_MSG = "aibot_respond_msg";
    public static final String CMD_RESPOND_WELCOME_MSG = "aibot_respond_welcome_msg";
    public static final String CMD_RESPOND_UPDATE_MSG = "aibot_respond_update_msg";
    public static final String CMD_SEND_MSG = "aibot_send_msg";
    public static final String CMD_UPLOAD_MEDIA_INIT = "aibot_upload_media_init";
    public static final String CMD_UPLOAD_MEDIA_CHUNK = "aibot_upload_media_chunk";
    public static final String CMD_UPLOAD_MEDIA_FINISH = "aibot_upload_media_finish";

    // =========================================================================
    // 消息类型
    // =========================================================================

    public static final String MSG_TYPE_TEXT = "text";
    public static final String MSG_TYPE_IMAGE = "image";
    public static final String MSG_TYPE_MIXED = "mixed";
    public static final String MSG_TYPE_VOICE = "voice";
    public static final String MSG_TYPE_FILE = "file";
    public static final String MSG_TYPE_VIDEO = "video";
    public static final String MSG_TYPE_STREAM = "stream";
    public static final String MSG_TYPE_MARKDOWN = "markdown";
    public static final String MSG_TYPE_EVENT = "event";
    public static final String MSG_TYPE_CARD = "template_card";

    // =========================================================================
    // 事件类型（服务端推送的事件）
    // =========================================================================

    public static final String EVENT_TYPE_ENTER_CHAT = "enter_chat";
    public static final String EVENT_TYPE_TEMPLATE_CARD = "template_card_event";
    public static final String EVENT_TYPE_FEEDBACK = "feedback_event";
    public static final String EVENT_TYPE_DISCONNECTED = "disconnected_event";

    // =========================================================================
    // 会话类型（字符串）
    // =========================================================================

    public static final String CHAT_TYPE_SINGLE = "single";
    public static final String CHAT_TYPE_GROUP = "group";

    // =========================================================================
    // 会话类型（数字，用于主动推送消息）
    // =========================================================================

    public static final int CHAT_TYPE_INT_SINGLE = 1;
    public static final int CHAT_TYPE_INT_GROUP = 2;

    // =========================================================================
    // SDK 内部事件名（事件总线使用）
    // =========================================================================

    public static final String EVENT_CONNECTED = "connected";
    public static final String EVENT_AUTHENTICATED = "authenticated";
    public static final String EVENT_DISCONNECTED = "disconnected";
    public static final String EVENT_RECONNECTING = "reconnecting";
    public static final String EVENT_ERROR = "error";
    public static final String EVENT_MESSAGE = "message";
    public static final String EVENT_MESSAGE_TEXT = "message.text";
    public static final String EVENT_MESSAGE_IMAGE = "message.image";
    public static final String EVENT_MESSAGE_MIXED = "message.mixed";
    public static final String EVENT_MESSAGE_VOICE = "message.voice";
    public static final String EVENT_MESSAGE_FILE = "message.file";
    public static final String EVENT_MESSAGE_VIDEO = "message.video";
    public static final String EVENT_EVENT = "event";
    public static final String EVENT_ENTER_CHAT = "event.enter_chat";
    public static final String EVENT_TEMPLATE_CARD = "event.template_card_event";
    public static final String EVENT_FEEDBACK = "event.feedback_event";

    // =========================================================================
    // 默认配置
    // =========================================================================

    /** 生产环境 WebSocket 端点地址 */
    public static final String DEFAULT_WS_URL = "wss://openws.work.weixin.qq.com";

    /** 推荐的心跳间隔（毫秒） */
    public static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 30_000L;

    /** 指数退避的初始延迟（毫秒） */
    public static final long DEFAULT_RECONNECT_BASE_DELAY_MS = 1_000L;

    /** 指数退避的延迟上限（毫秒） */
    public static final long DEFAULT_RECONNECT_MAX_DELAY_MS = 30_000L;

    /** 最大重连次数；-1 表示无限重连 */
    public static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 10;

    /** 单帧写入超时时间（毫秒） */
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 10_000L;

    /** 流式消息最大持续时间（毫秒） */
    public static final long STREAM_MAX_DURATION_MS = 10 * 60 * 1000L;

    /** 流式消息接近超时警告阈值（毫秒） */
    public static final long STREAM_WARN_THRESHOLD_MS = 30_000L;

    /** 分片上传的单片大小（字节） */
    public static final int UPLOAD_CHUNK_SIZE = 512 * 1024;
}
