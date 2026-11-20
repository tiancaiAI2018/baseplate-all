package com.example.baseplate.agent.adapter.in.web;

import com.example.baseplate.agent.adapter.out.persistence.AgentObserveRepository.ObserveSessionRow;
import com.example.baseplate.agent.application.AgentObserveService;
import com.example.baseplate.agent.application.AgentObserveService.SessionFilter;
import com.example.baseplate.api.annotation.RequiresPermission;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.api.port.out.CurrentUserProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 智能体观测：谁在哪个页面用了哪个 Agent、每轮的思考 / 工具调用回放、
 * token 与耗时指标、有用 / 没用评分、实际发出的模型请求体。
 * 只读，权限 agent:metrics:view。
 */
@RestController
@RequestMapping("/api/agent/observe")
@RequiresPermission("agent:metrics:view")
public class AgentObserveController {

    private final AgentObserveService observeService;
    private final CurrentUserProvider currentUser;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public AgentObserveController(AgentObserveService observeService,
                                  CurrentUserProvider currentUser) {
        this.observeService = observeService;
        this.currentUser = currentUser;
    }

    @GetMapping("/sessions")
    public Map<String, Object> sessions(
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        AgentObserveService.PagedSessions paged = observeService.listSessions(
                tenant(), new SessionFilter(agentId, userId, keyword), page, size);
        return Map.of("total", paged.total(), "items", paged.items());
    }

    @GetMapping("/sessions/{id}")
    public AgentObserveService.SessionDetail session(@PathVariable long id) {
        return observeService.sessionDetail(tenant(), id);
    }

    @GetMapping("/agents")
    public List<AgentObserveService.AgentOption> agents() {
        return observeService.agentOptions(tenant());
    }

    /**
     * 导出 JSON 文件。scope=all 不带筛选导出全租户；scope=filtered 按当前筛选条件导出。
     * 每个会话：session 元数据 + calls（每次实际发给模型的 OpenAI 风格请求体）。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(defaultValue = "filtered") String scope,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "500") int limit) {
        boolean exportAll = "all".equalsIgnoreCase(scope);
        List<AgentObserveService.ExportedSession> data = observeService.exportSessions(tenant(),
                new SessionFilter(exportAll ? null : agentId,
                        exportAll ? null : userId,
                        exportAll ? null : keyword),
                limit);
        return attachment(data, "agent-observe-export-" + stamp() + ".json", data.size());
    }

    /** 单个会话导出：结构同批量导出的单个元素，微调样本就是它。 */
    @GetMapping("/sessions/{id}/export")
    public ResponseEntity<byte[]> exportOne(@PathVariable long id) {
        AgentObserveService.ExportedSession data = observeService.exportSession(tenant(), id);
        return attachment(List.of(data), "agent-session-" + id + "-" + stamp() + ".json", 1);
    }

    private ResponseEntity<byte[]> attachment(Object data, String filename, int count) {
        // Jackson 3 的 writeValueAsBytes 返回类型有变数，统一走 String 再转 UTF-8
        byte[] body = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(data)
                .getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=" + filename)
                .header("X-Export-Count", String.valueOf(count))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private String stamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    private String tenant() {
        CurrentUser user = currentUser.current();
        return user == null || user.tenantId() == null || user.tenantId().isBlank()
                ? "default" : user.tenantId();
    }
}
