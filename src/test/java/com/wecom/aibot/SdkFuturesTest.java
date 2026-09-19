package com.wecom.aibot;

import org.junit.Test;

import static org.junit.Assert.*;

public class SdkFuturesTest {
    @Test public void unknownFailureKeepsAiBotExceptionShapeInsteadOfTimeout() throws Exception {
        // 旧契约把 UNKNOWN 重映射为 TimeoutException（诱导重试→重复发送）；统一为 AiBotException
        try {
            SdkFutures.await(SdkFutures.failed(new AiBotException(AiBotException.Code.UNKNOWN,"结果未知")));
            fail();
        } catch(AiBotException expected) { assertEquals(AiBotException.Code.UNKNOWN,expected.getCode()); }
    }
    @Test public void awaitIoPropagatesIOExceptionAsIs() throws Exception {
        try {
            SdkFutures.awaitIo(SdkFutures.failed(new AiBotException(AiBotException.Code.SERVER_REJECTED,"拒绝")));
            fail();
        } catch(AiBotException expected) { assertEquals(AiBotException.Code.SERVER_REJECTED,expected.getCode()); }
    }
}
