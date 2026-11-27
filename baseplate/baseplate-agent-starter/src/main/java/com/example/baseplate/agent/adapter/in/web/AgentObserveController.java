package com.example.baseplate.agent.adapter.in.web;

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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 智能体观测：谁在哪个页面用了哪个 Agent、每轮的思考 / 工具调用回放、
 * token 与耗时指标、有用 / 没用评分、实际发出的模型请求体。
 * 只读，权限 agent:metrics:view。
 *
 * <p>详情只返回合并后的请求（消息最多的一份），不返回每次调用的原始请求体；
 * 导出按会话流式写出，避免一次性把几百份请求体展开到内存。</p>
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
     * 每个会话：session 元数据 + request（合并后的完整请求体，含最终回复，与页面 JSON 视图一致）。
     *
     * <p>流式写出：每个会话序列化后立即输出，不把 500 份请求体同时展开成 Map。</p>
     */
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(defaultValue = "filtered") String scope,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "false") boolean pretty) {
        boolean exportAll = "all".equalsIgnoreCase(scope);
        List<AgentObserveService.ExportSource> sources = observeService.exportSources(tenant(),
                new SessionFilter(exportAll ? null : agentId,
                        exportAll ? null : userId,
                        exportAll ? null : keyword),
                limit);
        String filename = "agent-observe-export-" + stamp() + ".json";
        StreamingResponseBody body = out -> writeArray(out, sources, pretty);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .header("X-Export-Count", String.valueOf(sources.size()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** 单个会话导出：结构同批量导出的单个元素，微调样本就是它。 */
    @GetMapping("/sessions/{id}/export")
    public ResponseEntity<byte[]> exportOne(@PathVariable long id,
                                            @RequestParam(defaultValue = "true") boolean pretty) {
        AgentObserveService.ExportSource source = observeService.exportSource(tenant(), id);
        AgentObserveService.ExportedSession data = new AgentObserveService.ExportedSession(
                source.session(), observeService.mergedRequest(source));
        return attachment(data, "agent-session-" + id + "-" + stamp() + ".json", 1, pretty);
    }

    private void writeArray(OutputStream out, List<AgentObserveService.ExportSource> sources,
                            boolean pretty) throws IOException {
        out.write('[');
        boolean first = true;
        for (AgentObserveService.ExportSource source : sources) {
            if (!first) {
                out.write(',');
            }
            first = false;
            AgentObserveService.ExportedSession item = new AgentObserveService.ExportedSession(
                    source.session(), observeService.mergedRequest(source));
            String json = pretty
                    ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(item)
                    : mapper.writeValueAsString(item);
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        out.write(']');
    }

    private ResponseEntity<byte[]> attachment(Object data, String filename, int count,
                                              boolean pretty) {
        // Jackson 3 的 writeValueAsBytes 返回类型有变数，统一走 String 再转 UTF-8
        String json = pretty
                ? mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data)
                : mapper.writeValueAsString(data);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
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
