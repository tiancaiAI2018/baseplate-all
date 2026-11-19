package com.example.baseplate.agent.application;

import com.example.baseplate.agent.adapter.out.persistence.AgentMessageRepository;
import com.example.baseplate.agent.adapter.out.persistence.AgentSessionRepository;
import com.example.baseplate.agent.domain.AgentMessage;
import com.example.baseplate.agent.domain.AgentSession;
import com.example.baseplate.api.exception.BaseplateException;
import com.example.baseplate.api.exception.ForbiddenException;
import com.example.baseplate.api.model.CurrentUser;

import java.util.List;

import tools.jackson.databind.ObjectMapper;

/** 会话历史：本人只能看自己的，带 agent:session:view:all 才能看全租户。 */
public final class AgentSessionService {

    private static final int MAX_LIMIT = 200;

    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentSessionService(AgentSessionRepository sessions, AgentMessageRepository messages) {
        this.sessions = sessions;
        this.messages = messages;
    }

    public record MessageView(long id, int seq, String role, String content, Object blocks,
                              String model, int latencyMs, Object createdAt) {
    }

    public List<AgentSession> list(String tenant, CurrentUser user, boolean all, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        if (!all) {
            return sessions.listOwned(tenant, user.userId(), safeLimit);
        }
        requireViewAll(user);
        return sessions.listAll(tenant, safeLimit);
    }

    public List<MessageView> messages(String tenant, CurrentUser user, long sessionId, boolean all) {
        AgentSession session = all
                ? sessions.find(tenant, sessionId).orElse(null)
                : sessions.findOwned(tenant, user.userId(), sessionId).orElse(null);
        if (all) {
            requireViewAll(user);
        }
        if (session == null) {
            throw new BaseplateException("NOT_FOUND", "会话不存在");
        }
        List<AgentMessage> rows = messages.list(sessionId);
        return rows.stream().map(row -> new MessageView(
                row.id(), row.seq(), row.role(), row.content(),
                parseBlocks(row.blocksJson()), row.model(), row.latencyMs(), row.createdAt())).toList();
    }

    public void delete(String tenant, CurrentUser user, long sessionId) {
        sessions.softDelete(tenant, user.userId(), sessionId);
    }

    private void requireViewAll(CurrentUser user) {
        if (user.permissions() == null || !user.permissions().contains("agent:session:view:all")) {
            throw new ForbiddenException("缺少权限：agent:session:view:all");
        }
    }

    private Object parseBlocks(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, List.class);
    }
}
