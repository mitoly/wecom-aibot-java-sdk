package com.wecom.aibot;

import java.io.IOException;
import java.util.concurrent.*;

final class SdkFutures {
    private SdkFutures() {}
    static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>(); future.completeExceptionally(error); return future;
    }
    static <T> T await(CompletionStage<T> stage) throws IOException, InterruptedException {
        try { return stage.toCompletableFuture().get(); }
        catch (ExecutionException e) {
            Throwable cause = unwrap(e);
            // UNKNOWN 等结果不明错误统一以 AiBotException 抛出：TimeoutException 形态会诱导调用方按惯性重试，造成消息重复发送。
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("SDK 操作失败", cause);
        }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
    }
    static <T> T awaitStrict(CompletionStage<T> stage) throws IOException, InterruptedException {
        try { return stage.toCompletableFuture().get(); }
        catch(ExecutionException e) {Throwable cause=unwrap(e);if(cause instanceof IOException)throw (IOException)cause;throw new IOException("SDK 操作失败",cause);}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw e;}
    }
    static <T> T awaitIo(CompletionStage<T> stage) throws IOException {
        try { return await(stage); }
        catch (InterruptedException e) { throw new IOException("等待被中断；已发送请求可能仍在执行", e); }
    }
    static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error=error.getCause();
        return error;
    }
}
