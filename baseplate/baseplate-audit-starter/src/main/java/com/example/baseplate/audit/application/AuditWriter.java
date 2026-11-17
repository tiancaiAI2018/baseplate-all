package com.example.baseplate.audit.application;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计写入：异步单线程 + 有界队列。队列满时丢弃并计数，
 * 绝不让审计把业务请求拖死（审计是旁路，不是主链路）。
 */
public final class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    private record Entry(String sql, Object[] args) {
    }

    private final JdbcTemplate jdbc;
    private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(10_000);
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;
    private Thread worker;

    public AuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void start() {
        worker = Thread.ofVirtual().name("audit-writer").start(this::loop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    public void access(String tenant, String traceId, String actor, String action,
                       String resource, String resultCode, String clientIp, String userAgent,
                       Integer durationMs, String requestQuery, String detailJson) {
        enqueue(new Entry("INSERT INTO aud_access_log(tenant_id, trace_id, actor, action, resource,"
                        + " result_code, client_ip, user_agent, duration_ms, request_query, detail)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                new Object[]{tenant, traceId, actor, action, resource, resultCode, clientIp,
                        userAgent, durationMs, requestQuery,
                        detailJson == null ? "{}" : detailJson}));
    }

    public void business(String tenant, String traceId, String actor, String action,
                         String resource, String beforeJson, String afterJson) {
        enqueue(new Entry("INSERT INTO aud_business_log(tenant_id, trace_id, actor, action, resource,"
                        + " before_json, after_json) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                new Object[]{tenant, traceId, actor, action, resource, beforeJson, afterJson}));
    }

    public long droppedCount() {
        return dropped.get();
    }

    private void enqueue(Entry entry) {
        if (!queue.offer(entry)) {
            dropped.incrementAndGet();
        }
    }

    private void loop() {
        while (running || !queue.isEmpty()) {
            try {
                Entry entry = queue.poll(500, TimeUnit.MILLISECONDS);
                if (entry == null) {
                    continue;
                }
                try {
                    jdbc.update(entry.sql(), entry.args());
                } catch (RuntimeException e) {
                    log.warn("审计写入失败：{}", e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
