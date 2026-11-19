package com.example.baseplate.workflow.adapter.out.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 流程草稿持久化：AI 生成的草稿先落库，部署仍走原有部署接口。 */
public final class WorkflowDraftRepository {

    private final JdbcTemplate jdbc;

    public WorkflowDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DraftRow(long id, String tenantId, String processKey, String name,
                           String description, String specJson, String bpmnXml,
                           String status, String createdBy, OffsetDateTime createdAt) {
    }

    private static final RowMapper<DraftRow> MAPPER = (rs, i) -> new DraftRow(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("process_key"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("spec"),
            rs.getString("bpmn_xml"),
            rs.getString("status"),
            rs.getString("created_by"),
            rs.getObject("created_at", OffsetDateTime.class));

    public long create(String tenant, String processKey, String name, String description,
                       String specJson, String bpmnXml, String createdBy) {
        Long id = jdbc.queryForObject("""
                INSERT INTO wf_process_draft
                    (tenant_id, process_key, name, description, spec, bpmn_xml, created_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                RETURNING id
                """, Long.class, tenant, processKey, name, description, specJson, bpmnXml, createdBy);
        return id == null ? 0L : id;
    }

    public Optional<DraftRow> find(String tenant, long id) {
        return jdbc.query("""
                SELECT * FROM wf_process_draft WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenant, id).stream().findFirst();
    }

    public List<DraftRow> list(String tenant, int limit) {
        return jdbc.query("""
                SELECT * FROM wf_process_draft
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC
                 LIMIT ?
                """, MAPPER, tenant, limit);
    }
}
