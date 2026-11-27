package com.example.baseplate.workflow.autoconfigure;

import com.example.baseplate.api.port.out.CurrentUserProvider;
import com.example.baseplate.api.port.out.DispatchFilter;
import com.example.baseplate.api.port.out.DispatchLifecycleListener;
import com.example.baseplate.api.port.out.DispatchScorer;
import com.example.baseplate.api.port.out.ProcessEnginePort;
import com.example.baseplate.api.port.out.RefusalPolicyHandler;
import com.example.baseplate.api.port.out.RefusalReasonValidator;
import com.example.baseplate.workflow.adapter.out.flowable.BpmnDiagramEnricher;
import com.example.baseplate.workflow.adapter.out.flowable.BpmnXmlValidator;
import com.example.baseplate.workflow.adapter.out.flowable.FlowableDispatchTaskGateway;
import com.example.baseplate.workflow.adapter.out.flowable.FlowableProcessEngineAdapter;
import com.example.baseplate.workflow.adapter.out.flowable.NodeConfigCompiler;
import com.example.baseplate.workflow.adapter.out.flowable.WorkflowOperationLogger;
import com.example.baseplate.workflow.adapter.out.persistence.AttendanceRepository;
import com.example.baseplate.workflow.adapter.out.persistence.DispatchRepository;
import com.example.baseplate.workflow.adapter.out.persistence.WorkflowConfigRepository;
import com.example.baseplate.workflow.adapter.out.persistence.WorkflowDraftRepository;
import com.example.baseplate.workflow.application.AssigneeResolver;
import com.example.baseplate.workflow.application.BpmnDraftComposer;
import com.example.baseplate.workflow.application.WorkflowDraftService;
import com.example.baseplate.workflow.application.dispatch.AgentOnlineRedispatchListener;
import com.example.baseplate.workflow.application.dispatch.BuiltinDispatchFilters;
import com.example.baseplate.workflow.application.dispatch.BuiltinDispatchScorers;
import com.example.baseplate.workflow.application.dispatch.BuiltinRefusalHandlers;
import com.example.baseplate.workflow.application.dispatch.CheckinService;
import com.example.baseplate.workflow.application.dispatch.DispatchEvaluator;
import com.example.baseplate.workflow.application.dispatch.DispatchTaskGateway;
import com.example.baseplate.workflow.application.dispatch.DispatchTimeoutService;
import com.example.baseplate.workflow.application.dispatch.RefusalService;
import com.example.baseplate.workflow.application.dispatch.WorkPoolResolver;
import com.example.baseplate.workflow.application.dispatch.WorkflowDispatchService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** workflow 可选能力：classpath 有 Flowable 且容器里有 ProcessEngine 才装配。 */
@AutoConfiguration
@AutoConfigureAfter(name = "org.flowable.spring.boot.ProcessEngineAutoConfiguration")
@ConditionalOnClass(ProcessEngine.class)
@EnableScheduling
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

    // ---------------- 分单 ----------------

    @Bean
    @ConditionalOnMissingBean
    public DispatchRepository dispatchRepository(ObjectProvider<DataSource> dataSource) {
        return new DispatchRepository(new JdbcTemplate(dataSource.getObject()));
    }

    @Bean
    @ConditionalOnMissingBean
    public AttendanceRepository attendanceRepository(ObjectProvider<DataSource> dataSource) {
        return new AttendanceRepository(new JdbcTemplate(dataSource.getObject()));
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkPoolResolver workPoolResolver(DispatchRepository repository) {
        return new WorkPoolResolver(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public DispatchTaskGateway dispatchTaskGateway(ObjectProvider<TaskService> taskService,
                                                   ObjectProvider<HistoryService> historyService,
                                                   ObjectProvider<RepositoryService> repositoryService,
                                                   ObjectProvider<RuntimeService> runtimeService) {
        return new FlowableDispatchTaskGateway(taskService, historyService, repositoryService, runtimeService);
    }

    /**
     * 内置过滤器/打分器 + 业务方通过 Bean 注册的扩展。
     * ObjectProvider 只收集外部实现，内置实现直接 new，避免「把自己也收集进来」。
     */
    @Bean
    @ConditionalOnMissingBean
    public DispatchEvaluator dispatchEvaluator(ObjectProvider<DispatchFilter> customFilters,
                                               ObjectProvider<DispatchScorer> customScorers,
                                               AttendanceRepository attendance) {
        List<DispatchFilter> filters = new ArrayList<>();
        filters.add(BuiltinDispatchFilters.checkin());
        filters.add(BuiltinDispatchFilters.cooldown());
        filters.add(BuiltinDispatchFilters.capacity());
        filters.add(BuiltinDispatchFilters.online());
        filters.add(BuiltinDispatchFilters.refused(attendance::hasRefused));
        customFilters.orderedStream().forEach(filters::add);

        List<DispatchScorer> scorers = new ArrayList<>();
        scorers.add(BuiltinDispatchScorers.leastLoad());
        scorers.add(BuiltinDispatchScorers.rating());
        scorers.add(BuiltinDispatchScorers.fairness());
        scorers.add(BuiltinDispatchScorers.skill());
        scorers.add(BuiltinDispatchScorers.weight());
        customScorers.orderedStream().forEach(scorers::add);
        return new DispatchEvaluator(filters, scorers);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowDispatchService workflowDispatchService(
            DispatchRepository repository, AttendanceRepository attendance, WorkPoolResolver poolResolver,
            DispatchEvaluator evaluator, DispatchTaskGateway gateway,
            ObjectProvider<DispatchLifecycleListener> listeners,
            @Value("${baseplate.workflow.zone:Asia/Shanghai}") String zone) {
        return new WorkflowDispatchService(repository, attendance, poolResolver, evaluator, gateway,
                listeners.orderedStream().toList(), ZoneId.of(zone));
    }

    @Bean
    @ConditionalOnMissingBean
    public CheckinService checkinService(AttendanceRepository attendance,
                                         ApplicationEventPublisher publisher,
                                         @Value("${baseplate.workflow.zone:Asia/Shanghai}") String zone) {
        return new CheckinService(attendance, publisher, ZoneId.of(zone));
    }

    /** 签到上线实时重派开关，默认开；关闭后仅靠超时扫描兜底。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "baseplate.workflow.dispatch.checkin-redispatch-enabled",
            havingValue = "true", matchIfMissing = true)
    public AgentOnlineRedispatchListener agentOnlineRedispatchListener(
            WorkflowDispatchService dispatchService) {
        return new AgentOnlineRedispatchListener(dispatchService);
    }

    @Bean
    @ConditionalOnMissingBean
    public RefusalService refusalService(DispatchRepository dispatchRepository,
                                         AttendanceRepository attendance,
                                         WorkPoolResolver poolResolver,
                                         DispatchTaskGateway gateway,
                                         ObjectProvider<RefusalPolicyHandler> customHandlers,
                                         ObjectProvider<RefusalReasonValidator> validators,
                                         ObjectProvider<DispatchLifecycleListener> listeners) {
        List<RefusalPolicyHandler> handlers = new ArrayList<>();
        handlers.add(BuiltinRefusalHandlers.excludeTask());
        handlers.add(BuiltinRefusalHandlers.cooldown(attendance));
        handlers.add(BuiltinRefusalHandlers.block(attendance));
        handlers.add(BuiltinRefusalHandlers.weight(attendance));
        handlers.add(BuiltinRefusalHandlers.notifyHandler());
        handlers.add(BuiltinRefusalHandlers.escalate());
        customHandlers.orderedStream().forEach(handlers::add);
        return new RefusalService(dispatchRepository, attendance, poolResolver, gateway, handlers,
                validators.orderedStream().toList(), listeners.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public DispatchTimeoutService dispatchTimeoutService(
            DispatchRepository repository, WorkflowDispatchService dispatchService,
            @Value("${baseplate.workflow.dispatch.pending-timeout-minutes:30}") int pendingTimeoutMinutes,
            @Value("${baseplate.workflow.dispatch.handle-timeout-minutes:0}") int handleTimeoutMinutes) {
        return new DispatchTimeoutService(repository, dispatchService, pendingTimeoutMinutes,
                handleTimeoutMinutes);
    }
}
