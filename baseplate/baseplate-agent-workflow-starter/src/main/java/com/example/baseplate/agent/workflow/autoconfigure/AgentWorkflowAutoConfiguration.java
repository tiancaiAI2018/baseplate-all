package com.example.baseplate.agent.workflow.autoconfigure;

import com.example.baseplate.agent.autoconfigure.AgentAutoConfiguration;
import com.example.baseplate.agent.tool.AgentToolProvider;
import com.example.baseplate.agent.workflow.FormAgentToolProvider;
import com.example.baseplate.agent.workflow.FormAgentTools;
import com.example.baseplate.agent.workflow.WorkflowAgentToolProvider;
import com.example.baseplate.agent.workflow.WorkflowAgentTools;
import com.example.baseplate.api.port.out.ProcessEnginePort;
import com.example.baseplate.metadata.application.MetadataService;
import com.example.baseplate.metadata.autoconfigure.MetadataAutoConfiguration;
import com.example.baseplate.workflow.application.WorkflowAccessService;
import com.example.baseplate.workflow.application.WorkflowDraftService;
import com.example.baseplate.workflow.autoconfigure.WorkflowAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * 粘合层装配：只有 agent 与 workflow 都在时才注册工作流/流程表单工具。
 * 任一模块缺席都不会留下半套工具定义。
 */
@AutoConfiguration(after = {AgentAutoConfiguration.class, WorkflowAutoConfiguration.class,
        MetadataAutoConfiguration.class})
@ConditionalOnClass(name = "com.example.baseplate.agent.tool.AgentToolProvider")
@ConditionalOnBean({WorkflowDraftService.class, ProcessEnginePort.class,
        WorkflowAccessService.class})
public class AgentWorkflowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "workflowAgentToolProvider")
    public AgentToolProvider workflowAgentToolProvider(WorkflowDraftService draftService,
                                                       ProcessEnginePort engine,
                                                       WorkflowAccessService access) {
        return new WorkflowAgentToolProvider(
                new WorkflowAgentTools(draftService, engine, access));
    }

    /**
     * 表单工具单独一个 provider：页面与权限跟工作流工具不同，互不影响。
     * 条件按 bean 名判断——若按类型判断，会因为上面已有一个 AgentToolProvider 而永远不装配。
     */
    @Bean
    @ConditionalOnMissingBean(name = "formAgentToolProvider")
    @ConditionalOnBean(MetadataService.class)
    public AgentToolProvider formAgentToolProvider(MetadataService metadataService) {
        return new FormAgentToolProvider(new FormAgentTools(metadataService));
    }
}
