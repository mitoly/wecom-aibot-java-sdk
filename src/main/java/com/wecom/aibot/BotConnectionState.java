package com.wecom.aibot;

/** 客户端状态；FAILED、SUPERSEDED、CLOSED 都是终态。 */
public enum BotConnectionState { STOPPED, CONNECTING, AUTHENTICATING, READY, BACKOFF, SUPERSEDED, FAILED, CLOSED }
