package com.example.baseplate.workflow.autoconfigure;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.ProcessEnginePort;
import com.example.baseplate.workflow.adapter.out.flowable.BpmnDiagramEnricher;
import com.example.baseplate.workflow.adapter.out.flowable.BpmnXmlValidator;
import com.example.baseplate.workflow.adapter.out.flowable.FlowableProcessEngineAdapter;
import com.example.baseplate.workflow.adapter.out.flowable.NodeConfigCompiler;
import com.example.baseplate.workflow.adapter.out.flowable.WorkflowOperationLogger;
import com.example.baseplate.workflow.adapter.out.persistence.WorkflowConfigRepository;
import com.example.baseplate.workflow.adapter.out.persistence.WorkflowDraftRepository;
import com.example.baseplate.workflow.application.AssigneeResolver;
import com.example.baseplate.workflow.application.BpmnDraftComposer;
import com.example.baseplate.workflow.application.WorkflowDraftService;
import org.flowable.engine.ProcessEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/** workflow 可选能力：classpath 有 Flowable 且容器里有 ProcessEngine 才装配。 */
@AutoConfiguration
@AutoConfigureAfter(name = "org.flowable.spring.boot.ProcessEngineAutoConfiguration")
@ConditionalOnClass(ProcessEngine.class)
public class WorkflowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BpmnXmlValidator bpmnXmlValidator() {
        return new BpmnXmlValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public BpmnDiagramEnricher bpmnDiagramEnricher() {
        return new BpmnDiagramEnricher();
    }

    @Bean
    @ConditionalOnMissingBean
    public NodeConfigCompiler nodeConfigCompiler() {
        return new NodeConfigCompiler();
    }

    /**
     * 不用 @ConditionalOnBean(JdbcTemplate)：自动配置顺序早于 JdbcTemplateAutoConfiguration 时
     * 条件会误判。拿 DataSource 自己建，拿不到就退化成 no-op。
     */
    @Bean
    @ConditionalOnMissingBean
    public WorkflowOperationLogger workflowOperationLogger(ObjectProvider<DataSource> dataSource) {
        DataSource available = dataSource.getIfAvailable();
        return new WorkflowOperationLogger(available == null ? null : new JdbcTemplate(available));
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowConfigRepository workflowConfigRepository(ObjectProvider<DataSource> dataSource) {
        DataSource available = dataSource.getIfAvailable();
        return new WorkflowConfigRepository(available == null ? null : new JdbcTemplate(available));
    }

    @Bean
    @ConditionalOnMissingBean
    public BpmnDraftComposer bpmnDraftComposer() {
        return new BpmnDraftComposer();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowDraftRepository workflowDraftRepository(ObjectProvider<DataSource> dataSource) {
        return new WorkflowDraftRepository(new JdbcTemplate(dataSource.getObject()));
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowDraftService workflowDraftService(WorkflowDraftRepository repository,
                                                     BpmnDraftComposer composer) {
        return new WorkflowDraftService(repository, composer);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssigneeResolver assigneeResolver(WorkflowConfigRepository repository) {
        return new AssigneeResolver(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public com.example.baseplate.workflow.application.WorkflowLabInitializer workflowLabInitializer(
            ObjectProvider<DataSource> dataSource, WorkflowConfigRepository repository,
            ObjectProvider<ProcessEnginePort> enginePort) {
        DataSource available = dataSource.getIfAvailable();
        return new com.example.baseplate.workflow.application.WorkflowLabInitializer(
                available == null ? null : new JdbcTemplate(available),
                repository, enginePort.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public com.example.baseplate.workflow.application.WorkflowAccessService workflowAccessService(
            WorkflowConfigRepository repository,
            ObjectProvider<com.example.baseplate.api.port.out.DataScopeProvider> dataScope,
            ObjectProvider<ProcessEnginePort> enginePort) {
        return new com.example.baseplate.workflow.application.WorkflowAccessService(
                repository, dataScope.getIfAvailable(), enginePort.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ProcessEnginePort processEnginePort(ObjectProvider<ProcessEngine> engine,
                                               BpmnXmlValidator validator,
                                               BpmnDiagramEnricher diagramEnricher,
                                               NodeConfigCompiler compiler,
                                               WorkflowConfigRepository configRepository,
                                               AssigneeResolver assigneeResolver,
                                               ObjectProvider<WorkflowOperationLogger> operations,
                                               ObjectProvider<CurrentUserProvider> currentUser,
                                               JdbcTemplate jdbcTemplate) {
        ProcessEngine available = engine.getIfAvailable();
        if (available == null) {
            return new com.example.baseplate.workflow.adapter.out.flowable.UnavailableProcessEnginePort();
        }
        return new FlowableProcessEngineAdapter(available, validator, diagramEnricher, compiler,
                configRepository, assigneeResolver, operations.getIfAvailable(), currentUser.getIfAvailable(),
                jdbcTemplate);
    }
}
