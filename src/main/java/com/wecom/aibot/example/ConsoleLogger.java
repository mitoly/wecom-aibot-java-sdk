package com.wecom.aibot.example;

import com.wecom.aibot.AiBotLogger;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;

/** 演示程序的控制台日志；业务应用可直接使用自身的 SLF4J 实现。 */
public final class ConsoleLogger implements AiBotLogger {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public void debug(String message, Object... args) {
        // 默认关闭 debug，避免示例打印大量业务数据。
    }

    @Override
    public void info(String message, Object... args) {
        write("INFO", message, args);
    }

    @Override
    public void warn(String message, Object... args) {
        write("WARN", message, args);
    }

    @Override
    public void error(String message, Object... args) {
        write("ERROR", message, args);
    }

    private void write(String level, String message, Object[] args) {
        String formatted = message;
        for (Object arg : args) {
            formatted = formatted.replaceFirst("\\{\\}", Matcher.quoteReplacement(String.valueOf(arg)));
        }
        System.out.println("[" + LocalTime.now().format(TIME) + "] [" + level + "] " + formatted);
    }
}
