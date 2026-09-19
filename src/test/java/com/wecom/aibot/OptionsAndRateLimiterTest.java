package com.wecom.aibot;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class OptionsAndRateLimiterTest {
    @Test public void configSnapshotAndValidation() throws Exception {
        Options source=new Options().setBotId("bot").setSecret("secret");Options snapshot=source.snapshot();source.setSecret("changed");assertEquals("secret",snapshot.getSecret());
        try{source.setHeartbeatIntervalMs(0).validate();fail();}catch(IllegalArgumentException expected){}
        try{new Options().setBotId(" ").setSecret("s").validate();fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void downloadTimeoutsDefaultToLegacyValuesAndSnapshot() throws Exception {
        Options source=new Options().setBotId("bot").setSecret("secret").setDownloadConnectTimeoutMs(1234).setDownloadReadTimeoutMs(5678);
        assertEquals(1234,source.getDownloadConnectTimeoutMs());assertEquals(5678,source.getDownloadReadTimeoutMs());
        Options snapshot=source.snapshot();assertEquals(1234,snapshot.getDownloadConnectTimeoutMs());assertEquals(5678,snapshot.getDownloadReadTimeoutMs());
        assertEquals(30000L,new Options().getDownloadConnectTimeoutMs());assertEquals(60000L,new Options().getDownloadReadTimeoutMs());
        try{source.setDownloadReadTimeoutMs(0).validate();fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void minuteLimitAndRetryAfterUseRollingWindow() throws Exception {
        AtomicLong clock=new AtomicLong();RateLimiter limiter=new RateLimiter(clock::get);
        for(int i=0;i<30;i++)limiter.acquire("chat");
        try{limiter.acquire("chat");fail();}catch(RateLimitException error){assertEquals(60000,error.getRetryAfterMs());}
        limiter.acquire("other-chat");clock.set(60000000000L);limiter.acquire("chat");
    }
    @Test public void hourLimitCannotBeBypassedByMinuteWindows() throws Exception {
        AtomicLong clock=new AtomicLong();RateLimiter limiter=new RateLimiter(clock::get);
        for(int i=0;i<1000;i++){clock.set((i/30)*60000000000L);limiter.acquire("chat");}
        clock.set(34*60000000000L);
        try{limiter.acquire("chat");fail();}catch(RateLimitException error){assertEquals(1560000,error.getRetryAfterMs());}
        clock.set(3600000000000L);limiter.acquire("chat");
    }
}
