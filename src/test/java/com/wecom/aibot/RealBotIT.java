package com.wecom.aibot;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 显式 -Plive-bot 才执行；必须使用隔离测试机器人，不发送主动消息。 */
public class RealBotIT {
    @Test public void authenticatesWithIsolatedRobotAndCloses() throws Exception {
        String bot=System.getenv("WECHAT_BOT_ID"),secret=System.getenv("WECHAT_BOT_SECRET");
        assertTrue("live-bot 需要 WECHAT_BOT_ID",bot!=null&&!bot.trim().isEmpty());
        assertTrue("live-bot 需要 WECHAT_BOT_SECRET",secret!=null&&!secret.trim().isEmpty());
        WeComAiBotClient client=new WeComAiBotClient(new Options().setBotId(bot).setSecret(secret).setMaxAuthFailureAttempts(0).setMaxReconnectAttempts(0));
        try {client.startAsync().toCompletableFuture().get(30,TimeUnit.SECONDS);assertEquals(BotConnectionState.READY,client.getState());}
        finally {client.close();}
        assertEquals(BotConnectionState.CLOSED,client.getState());
    }
}
