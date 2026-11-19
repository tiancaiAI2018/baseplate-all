package com.example.baseplate.workflow.application;

import com.example.baseplate.api.annotation.AuditLog;
import com.example.baseplate.api.exception.BaseplateException;
import com.example.baseplate.workflow.adapter.out.persistence.WorkflowDraftRepository;
import com.example.baseplate.workflow.application.BpmnDraftComposer.DraftEdge;
import com.example.baseplate.workflow.application.BpmnDraftComposer.DraftNode;
import com.example.baseplate.workflow.application.BpmnDraftComposer.DraftSpec;

import java.time.OffsetDateTime;
import java.util.List;

import tools.jackson.databind.ObjectMapper;

/**
 * 流程草稿用例：校验结构化节点 → 编译 BPMN → 落草稿。
 * 部署动作仍然由用户在画布上确认，AI 不直接发布流程。
 *
 * <p>注意：类不能声明为 final。{@code @AuditLog} 由 AOP 代理实现，
 * CGLIB 无法继承 final 类，运行期会直接启动失败。</p>
 */
public class WorkflowDraftService {

    private final WorkflowDraftRepository repository;
    private final BpmnDraftComposer composer;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowDraftService(WorkflowDraftRepository repository, BpmnDraftComposer composer) {
        this.repository = repository;
        this.composer = composer;
    }

    public record CreateRequest(String processKey, String name, String description,
                                List<DraftNode> nodes, List<DraftEdge> edges) {
    }

    public record DraftView(long id, String processKey, String name, String description,
                            String spec, String bpmnXml, String status, String createdBy,
                            OffsetDateTime createdAt) {
    }

    @AuditLog(action = "wf:create-draft", resource = "wf_process_draft")
    public DraftView create(String tenant, String createdBy, CreateRequest request) {
        // 先归一化连线（省略 edges 时线性串联、漏 START 出线时补一条），
        // 再同时用于编译与落库，避免草稿里的 spec 和 BPMN 对不上
        List<DraftEdge> edges = composer.normalizeEdges(
                request.nodes() == null ? List.of() : request.nodes(), request.edges());
        CreateRequest normalized = new CreateRequest(request.processKey(), request.name(),
                request.description(), request.nodes(), edges);
        DraftSpec spec = new DraftSpec(normalized.processKey(), normalized.name(),
                normalized.description(), normalized.nodes(), normalized.edges());
        String bpmnXml = composer.compose(spec);
        String specJson = mapper.writeValueAsString(normalized);
        long id = repository.create(tenant, normalized.processKey(), normalized.name(),
                normalized.description(), specJson, bpmnXml, createdBy);
        return repository.find(tenant, id).map(this::toView)
                .orElseThrow(() -> new BaseplateException("INTERNAL_ERROR", "流程草稿创建失败"));
    }

    public DraftView get(String tenant, long id) {
        return repository.find(tenant, id).map(this::toView)
                .orElseThrow(() -> new BaseplateException("NOT_FOUND", "流程草稿不存在"));
    }

    public List<DraftView> list(String tenant, int limit) {
        return repository.list(tenant, Math.max(1, Math.min(limit, 100))).stream()
                .map(this::toView).toList();
    }

    private DraftView toView(WorkflowDraftRepository.DraftRow row) {
        return new DraftView(row.id(), row.processKey(), row.name(), row.description(),
                row.specJson(), row.bpmnXml(), row.status(), row.createdBy(), row.createdAt());
    }
}
