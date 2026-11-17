package com.example.baseplate.workflow.adapter.out.flowable;

import com.example.baseplate.api.model.WorkflowOperationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流操作流水。引擎历史表只记「发生了什么」，这里记「谁、因为什么、对哪个节点」。
 * 写入走旁路：表不存在或写失败只告警，绝不让流程办理失败。
 */
public final class WorkflowOperationLogger {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOperationLogger.class);

    private final JdbcTemplate jdbc;

    public WorkflowOperationLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String action, String processInstanceId, String processDefinitionId,
                       String taskId, String activityId, String decision, String reason,
                       String operator, String targetNodeId) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("INSERT INTO wf_operation_log(tenant_id, process_instance_id, process_definition_id,"
                            + " task_id, activity_id, action, decision, reason, operator, target_node_id)"
                            + " VALUES ('default', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    processInstanceId, processDefinitionId, taskId, activityId, action,
                    decision, reason, operator, targetNodeId);
        } catch (RuntimeException ex) {
            log.warn("工作流操作流水写入失败 action={} instance={} cause={}",
                    action, processInstanceId, ex.getMessage());
        }
    }

    public List<WorkflowOperationView> list(String processInstanceId) {
        if (jdbc == null || processInstanceId == null) {
            return List.of();
        }
        try {
            return jdbc.query("SELECT id, action, decision, reason, operator, activity_id,"
                            + " target_node_id, task_id, occurred_at FROM wf_operation_log"
                            + " WHERE process_instance_id = ? ORDER BY id",
                    (rs, i) -> new WorkflowOperationView(
                            rs.getLong("id"), rs.getString("action"), rs.getString("decision"),
                            rs.getString("reason"), rs.getString("operator"),
                            rs.getString("activity_id"), rs.getString("target_node_id"),
                            rs.getString("task_id"),
                            rs.getTimestamp("occurred_at") == null ? null
                                    : rs.getTimestamp("occurred_at").toInstant()),
                    processInstanceId);
        } catch (RuntimeException ex) {
            log.warn("工作流操作流水读取失败 instance={} cause={}", processInstanceId, ex.getMessage());
            return List.of();
        }
    }

    /** 「打回」= 主动回退 + 驳回，两者都算一次。 */
    public int rollbackCount(String processInstanceId) {
        if (jdbc == null || processInstanceId == null) {
            return 0;
        }
        try {
            Long count = jdbc.queryForObject("SELECT count(*) FROM wf_operation_log"
                            + " WHERE process_instance_id = ?"
                            + " AND (action = 'ROLLBACK' OR decision = 'REJECT')",
                    Long.class, processInstanceId);
            return count == null ? 0 : count.intValue();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    public List<String> rolledBackActivities(String processInstanceId) {
        if (jdbc == null || processInstanceId == null) {
            return List.of();
        }
        try {
            return new ArrayList<>(jdbc.queryForList(
                    "SELECT DISTINCT activity_id FROM wf_operation_log"
                            + " WHERE process_instance_id = ? AND activity_id IS NOT NULL"
                            + " AND (action = 'ROLLBACK' OR decision = 'REJECT')",
                    String.class, processInstanceId));
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
