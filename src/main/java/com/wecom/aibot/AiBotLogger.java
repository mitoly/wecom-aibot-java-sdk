package com.wecom.aibot;

import org.slf4j.LoggerFactory;

/**
 * SDK 日志接口。
 * <p>
 * 默认使用 SLF4J 实现，用户可自定义实现此接口接入任意日志框架。
 */
public interface AiBotLogger {

    void debug(String msg, Object... args);

    void info(String msg, Object... args);

    void warn(String msg, Object... args);

    void error(String msg, Object... args);

    /**
     * 基于 SLF4J 的默认日志实现。
     */
    class Slf4jLogger implements AiBotLogger {
        private final org.slf4j.Logger logger;

        public Slf4jLogger() {
            this.logger = LoggerFactory.getLogger("wecom-aibot");
        }

        public Slf4jLogger(String name) {
            this.logger = LoggerFactory.getLogger(name);
        }

        @Override
        public void debug(String msg, Object... args) {
            logger.debug(msg, args);
        }

        @Override
        public void info(String msg, Object... args) {
            logger.info(msg, args);
        }

        @Override
        public void warn(String msg, Object... args) {
            logger.warn(msg, args);
        }

        @Override
        public void error(String msg, Object... args) {
            logger.error(msg, args);
        }
    }

    /**
     * 丢弃所有日志输出的静默实现。
     */
    class NopLogger implements AiBotLogger {
        @Override
        public void debug(String msg, Object... args) {
        }

        @Override
        public void info(String msg, Object... args) {
        }

        @Override
        public void warn(String msg, Object... args) {
        }

        @Override
        public void error(String msg, Object... args) {
        }
    }
}
