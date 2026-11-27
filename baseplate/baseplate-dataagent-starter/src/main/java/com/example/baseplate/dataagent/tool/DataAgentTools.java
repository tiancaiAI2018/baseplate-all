package com.example.baseplate.dataagent.tool;

import com.example.baseplate.agent.runtime.UiAwareToolResultConverter;
import com.example.baseplate.api.model.CurrentUser;
import com.example.baseplate.dataagent.adapter.out.persistence.DataMetadataRepository;
import com.example.baseplate.dataagent.application.SqlGuard;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** DataAgent 工具实现：自然语言 -> 表结构发现 -> 只读 SQL -> 数据卡片。 */
public final class DataAgentTools {

    private static final Map<String, String> SEMANTIC = Map.of(
            "销售额", "订单金额字段，通常是 t_order.amount 或 t_order.pay_amount",
            "订单", "t_order，按 create_time 与 status 过滤",
            "用户", "t_user，注意区分 create_user 与负责人",
            "待办", "当前用户待处理任务，见 workflow 相关表",
            "流程", "流程实例与任务表，按 business_key 串联");

    private static final Set<String> SENSITIVE = Set.of(
            "password", "password_hash", "api_key", "api_key_enc", "secret", "secret_key",
            "refresh_token", "access_token", "token_hash");

    private static final Map<String, String> KNOWLEDGE = Map.of(
            "待办", "待办 = 分配给当前用户且未完成的任务，不含已办与已发起",
            "销售额", "销售额按支付金额统计，退款订单需排除 status = REFUNDED",
            "活跃用户", "近 30 天有过登录或下单记录的用户，去重后计数");

    private final DataMetadataRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public DataAgentTools(DataMetadataRepository repository) {
        this.repository = repository;
    }

    @Tool(name = "datasource.sales_db.search",
            converter = UiAwareToolResultConverter.class,
            description = "数据探查与查询的统一入口。用户问数据、指标、报表、图表时使用。"
                    + "action 支持：LIST_TABLES（可见表）、FIND_TABLES(query)（按关键词找表）、"
                    + "GET_TABLE_SCHEMA(tableName)（列/类型/主键）、GET_RELATED_TABLES(tableName)（关联表与 JOIN 关系）、"
                    + "PREVIEW_ROWS(tableName)（样例行，非默认步骤）、SEARCH(sql)（执行只读 SQL，给出数据与图表）。"
                    + "先 LIST/FIND 找表，再 GET_TABLE_SCHEMA/GET_RELATED_TABLES 确认字段与关联，最后 SEARCH。")
    public String search(RuntimeContext ctx,
                         @ToolParam(name = "action", description = "上面的 action 枚举之一") String action,
                         @ToolParam(name = "query", required = false, description = "FIND_TABLES 的关键词") String query,
                         @ToolParam(name = "tableName", required = false, description = "表名") String tableName,
                         @ToolParam(name = "sql", required = false, description = "SELECT / WITH 只读 SQL") String sql,
                         @ToolParam(name = "limit", required = false, description = "行数上限，默认 50") Integer limit) {
        requirePermission(ctx);
        String op = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        int maxRows = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", op);
        switch (op) {
            case "LIST_TABLES" -> {
                List<String> tables = repository.visibleTables();
                result.put("summary", "当前可见 " + tables.size() + " 张表");
                result.put("tables", tables);
                result.put("ui", Map.of("kind", "table", "title", "可见表",
                        "columns", List.of("table_name"),
                        "rows", tables.stream().map(t -> Map.of("table_name", t)).toList()));
            }
            case "FIND_TABLES" -> {
                List<String> tables = repository.findTables(query);
                result.put("summary", tables.isEmpty()
                        ? "没有匹配的表，试试英文关键词或 LIST_TABLES" : "匹配到 " + tables.size() + " 张表");
                result.put("tables", tables);
            }
            case "GET_TABLE_SCHEMA" -> {
                requireTable(tableName);
                List<Map<String, Object>> rows = repository.columns(tableName).stream()
                        .map(DataMetadataRepository::cell).toList();
                result.put("summary", tableName + " 共 " + rows.size() + " 列");
                result.put("columns", rows);
            }
            case "GET_RELATED_TABLES" -> {
                requireTable(tableName);
                List<Map<String, Object>> rows = repository.relatedTables(tableName).stream()
                        .map(DataMetadataRepository::cell).toList();
                List<String> hints = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    hints.add(row.get("table_name") + "." + row.get("column_name")
                            + " = " + row.get("related_table") + "." + row.get("related_column"));
                }
                result.put("summary", hints.isEmpty() ? "没有外键关联" : "JOIN 关系：" + String.join("; ", hints));
                result.put("relations", rows);
            }
            case "PREVIEW_ROWS" -> {
                requireTable(tableName);
                List<Map<String, Object>> rows = repository.preview(tableName, Math.min(maxRows, 20)).stream()
                        .map(DataMetadataRepository::cell).map(DataAgentTools::mask).toList();
                result.put("summary", "样例行 " + rows.size() + " 条（仅供理解字段，不代表统计结果）");
                result.put("rows", rows);
                result.put("ui", tableCard("样例数据：" + tableName, rows));
            }
            case "SEARCH" -> {
                List<Map<String, Object>> rows = repository.query(sql, maxRows).stream()
                        .map(DataMetadataRepository::cell).map(DataAgentTools::mask).toList();
                List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
                result.put("summary", "查询返回 " + rows.size() + " 行；列：" + String.join(", ", columns));
                result.put("rows", rows.subList(0, Math.min(rows.size(), 5)));
                result.put("ui", chartOrTable("查询结果", columns, rows));
            }
            default -> throw new IllegalArgumentException("未知 action：" + action
                    + "，可用 LIST_TABLES / FIND_TABLES / GET_TABLE_SCHEMA / GET_RELATED_TABLES / PREVIEW_ROWS / SEARCH");
        }
        return mapper.writeValueAsString(result);
    }

    @Tool(name = "semantic_model.search",
            description = "按中文业务词查字段/表的语义映射（别名、口径提示）。不确定用户说的词对应哪张表哪个字段时使用。")
    public String semanticSearch(RuntimeContext ctx,
                                 @ToolParam(name = "query", description = "用户说的业务词") String query) {
        requirePermission(ctx);
        String key = query == null ? "" : query.trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", key);
        result.put("meaning", SEMANTIC.getOrDefault(key, "没有预设映射，请用 FIND_TABLES / GET_TABLE_SCHEMA 自行确认"));
        return mapper.writeValueAsString(result);
    }

    @Tool(name = "domain_business_knowledge.search",
            description = "查业务口径定义（例如待办、销售额、活跃用户怎么算）。生成 SQL 前先确认口径。")
    public String knowledgeSearch(RuntimeContext ctx,
                                  @ToolParam(name = "query", description = "业务术语") String query) {
        requirePermission(ctx);
        String key = query == null ? "" : query.trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", key);
        result.put("definition", KNOWLEDGE.getOrDefault(key, "没有预设口径，按用户描述并说明你的假设"));
        return mapper.writeValueAsString(result);
    }

    @Tool(name = "sql_guard.check",
            description = "校验只读 SQL 是否安全，并补 LIMIT。执行 SEARCH 前可先调用。")
    public String guard(RuntimeContext ctx,
                        @ToolParam(name = "sql", description = "待校验的 SELECT / WITH SQL") String sql) {
        requirePermission(ctx);
        SqlGuard.Result checked = SqlGuard.check(sql, 200);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowed", checked.allowed());
        result.put("reason", checked.reason());
        result.put("sql", checked.sql());
        return mapper.writeValueAsString(result);
    }

    private Map<String, Object> chartOrTable(String title, List<String> columns,
                                             List<Map<String, Object>> rows) {
        if (rows.size() >= 2 && columns.size() >= 2) {
            String category = columns.get(0);
            for (String column : columns.subList(1, columns.size())) {
                List<Double> numbers = new ArrayList<>();
                boolean numeric = true;
                for (Map<String, Object> row : rows) {
                    try {
                        numbers.add(Double.parseDouble(String.valueOf(row.get(column))));
                    } catch (RuntimeException e) {
                        numeric = false;
                        break;
                    }
                }
                if (numeric) {
                    List<String> categories = rows.stream()
                            .map(row -> String.valueOf(row.get(category))).toList();
                    return Map.of("kind", "chart", "title", title, "chartType", "bar",
                            "categories", categories,
                            "series", List.of(Map.of("name", column, "data", numbers)));
                }
            }
        }
        return tableCard(title, rows);
    }

    private Map<String, Object> tableCard(String title, List<Map<String, Object>> rows) {
        return Map.of("kind", "table", "title", title,
                "columns", rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet()),
                "rows", rows);
    }

    private static Map<String, Object> mask(Map<String, Object> row) {
        for (String key : SENSITIVE) {
            if (row.containsKey(key) && row.get(key) != null) {
                row.put(key, "***");
            }
        }
        return row;
    }

    private static void requireTable(String table) {
        if (!SqlGuard.validTable(table)) {
            throw new IllegalArgumentException("非法表名：" + table);
        }
    }

    private static void requirePermission(RuntimeContext ctx) {
        CurrentUser user = ctx == null ? null : ctx.get("principal");
        if (user == null || (user.permissions() == null || !user.permissions().contains("agent:chat"))
                && (user.roles() == null || !user.roles().contains("SUPER_ADMIN"))) {
            throw new IllegalArgumentException("当前用户没有 agent:chat 权限");
        }
    }
}
