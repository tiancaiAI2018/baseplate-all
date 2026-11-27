package com.example.baseplate.dataagent.tool;

import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.domain.RiskLevel;
import com.example.baseplate.agent.domain.ToolType;
import com.example.baseplate.agent.tool.AgentToolProvider;
import com.example.baseplate.agent.tool.AgentToolSpec;
import com.example.baseplate.agent.tool.ToolDescriptor;

import java.util.List;
import java.util.Set;

/** 数据助手工具声明：四个只读工具，全部走入口工具分发。 */
public final class DataAgentToolProvider implements AgentToolProvider {

    private final DataAgentTools tools;

    public DataAgentToolProvider(DataAgentTools tools) {
        this.tools = tools;
    }

    @Override
    public List<AgentToolSpec> tools() {
        return List.of(
                spec("datasource.sales_db.search", "数据探查与查询",
                        "LIST_TABLES / FIND_TABLES / GET_TABLE_SCHEMA / GET_RELATED_TABLES / PREVIEW_ROWS / SEARCH。用户问数据或图表时先调用它。"),
                spec("semantic_model.search", "字段语义检索",
                        "按中文业务词查表与字段的语义映射，不确定用户说的词对应什么时使用。"),
                spec("domain_business_knowledge.search", "业务口径检索",
                        "查待办、销售额、活跃用户等业务术语的统计口径，生成 SQL 前先确认。"),
                spec("sql_guard.check", "SQL 安全校验",
                        "校验只读 SQL 并补 LIMIT，SEARCH 前可先调用。"));
    }

    private AgentToolSpec spec(String code, String name, String description) {
        return new AgentToolSpec(new ToolDescriptor(
                code, name, description, ToolType.BUILTIN_INTERNAL, true, false,
                Set.of(), "agent:chat", RiskLevel.READ, ApprovalMode.ALLOW, ApprovalMode.ALLOW),
                tools);
    }
}
