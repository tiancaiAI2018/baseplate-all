package com.example.baseplate.agent.runtime;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 输出封装：统一 seq、事件名与 JSON 负载。
 *
 * <p>seq 从 1 严格递增，前端按 seq 去重排序；心跳用注释帧，不占 seq。</p>
 */
public final class SseEventWriter {

    private final SseEmitter emitter;
    private final AtomicInteger seq = new AtomicInteger();

    public SseEventWriter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    public synchronized void send(String type, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", seq.incrementAndGet());
        payload.put("type", type);
        if (data != null) {
            payload.putAll(data);
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(payload.get("seq")))
                    .name(type)
                    .data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开：由调用方取消上游订阅，不在这里重试
            throw new ClientDisconnectedException(e);
        }
    }

    public synchronized void heartbeat() {
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | IllegalStateException e) {
            throw new ClientDisconnectedException(e);
        }
    }

    public void complete() {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 已完成的 emitter 再次 complete 会抛，忽略即可
        }
    }

    public void completeWithError(Throwable error) {
        try {
            emitter.completeWithError(error);
        } catch (RuntimeException ignored) {
            // 同上
        }
    }

    /** 客户端断开：用于把 Reactor 的订阅取消掉，停止继续消耗模型 token。 */
    public static final class ClientDisconnectedException extends RuntimeException {
        public ClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }
}
