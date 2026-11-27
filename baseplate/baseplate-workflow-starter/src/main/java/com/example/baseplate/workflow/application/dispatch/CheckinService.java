package com.example.baseplate.workflow.application.dispatch;

import com.example.baseplate.api.model.AgentOnlineEvent;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.workflow.adapter.out.persistence.AttendanceRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 签到：当前登录用户打卡，一天一条；签到决定能不能进候选池。
 *
 * <p>签到成功后在事务提交时发布 {@link AgentOnlineEvent}，
 * 由监听器对该坐席可见池里的待派任务立即重派——避免「发起时全员未签到、
 * 后来有人上线」却要等 30 分钟超时扫描才流转。心跳从非 ONLINE 转为
 * ONLINE 时同样发布，覆盖「签到时离线、后来才上线」的场景。
 */
public class CheckinService {

    private final AttendanceRepository repository;
    private final ApplicationEventPublisher publisher;
    private final ZoneId zone;

    public CheckinService(AttendanceRepository repository, ApplicationEventPublisher publisher, ZoneId zone) {
        this.repository = repository;
        this.publisher = publisher;
        this.zone = zone == null ? ZoneId.of("Asia/Shanghai") : zone;
    }

    public Map<String, Object> today(CurrentUser user) {
        LocalDate date = LocalDate.now(zone);
        Map<String, Object> row = repository.checkinOf(user.tenantId(), user.userId(), date).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("checkedIn", row != null && row.get("checkoutAt") == null);
        result.put("checkinAt", row == null ? null : row.get("checkinAt"));
        result.put("checkoutAt", row == null ? null : row.get("checkoutAt"));
        result.put("source", row == null ? null : row.get("source"));
        result.put("state", repository.agentState(user.tenantId(), user.userId()).orElse(Map.of()));
        result.put("history", repository.checkinHistory(user.tenantId(), user.userId(), 7));
        return result;
    }

    @Transactional
    public Map<String, Object> checkin(CurrentUser user) {
        repository.checkin(user.tenantId(), user.userId(), LocalDate.now(zone), "WEB");
        publishOnline(user, "CHECKIN");
        return today(user);
    }

    @Transactional
    public Map<String, Object> checkout(CurrentUser user) {
        repository.checkout(user.tenantId(), user.userId(), LocalDate.now(zone));
        return today(user);
    }

    @Transactional
    public Map<String, Object> heartbeat(CurrentUser user, String status) {
        String normalized = status == null || status.isBlank() ? "ONLINE" : status.toUpperCase();
        if (!List.of("ONLINE", "BUSY", "AWAY", "OFFLINE").contains(normalized)) {
            normalized = "ONLINE";
        }
        String before = repository.agentState(user.tenantId(), user.userId())
                .map(row -> (String) row.get("onlineStatus")).orElse("OFFLINE");
        repository.heartbeat(user.tenantId(), user.userId(), normalized);
        if (!"ONLINE".equals(before) && "ONLINE".equals(normalized)) {
            publishOnline(user, "HEARTBEAT");
        }
        return today(user);
    }

    public Map<String, Object> state(CurrentUser user) {
        return repository.agentState(user.tenantId(), user.userId()).orElse(Map.of());
    }

    private void publishOnline(CurrentUser user, String source) {
        publisher.publishEvent(new AgentOnlineEvent(
                user.tenantId(), user.userId(), source, Instant.now()));
    }
}
