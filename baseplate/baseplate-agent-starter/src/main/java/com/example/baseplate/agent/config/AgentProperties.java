package com.example.baseplate.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 智能体模块配置。
 *
 * <p>模型相关字段只作为「首次启动的种子配置」：控制台里保存的 Agent 配置优先，
 * 这样运维可以在 .env 里给一套默认值，业务人员随后在页面上按租户调整。</p>
 */
@ConfigurationProperties(prefix = "baseplate.agent")
public class AgentProperties {

    /** 未配置任何 Agent 时，用这组值创建 default 租户的默认助手。 */
    private String defaultModel = System.getenv().getOrDefault("BASEPLATE_AGENT_MODEL", "MiniMax-M3.1-Flash");
    private String defaultBaseUrl = System.getenv().getOrDefault("BASEPLATE_AGENT_BASE_URL", "https://api.minimax.cn/v1");
    private String defaultApiKey = System.getenv().getOrDefault("BASEPLATE_AGENT_API_KEY", "");

    /** 单轮 ReAct 最多循环次数；超过即停止，避免模型无限调用工具。 */
    private int maxIters = 4;
    /** 内部工具暴露方式（DIRECT / CLI / META），三者互斥，默认 CLI。 */
    private ToolExposureMode toolExposureMode = ToolExposureMode.CLI;
    /** 收敛模式下是否自动挂载入口技能（教模型如何使用 find/show/call 或元工具）。 */
    private boolean toolExposureSkill = true;
    /** 整条 SSE 流的最长等待时间（秒），与 SseEmitter 超时同源。 */
    private long streamTimeoutSeconds = 120;
    /** HITL 审批单有效期（秒）；过期后必须重新发起对话。 */
    private long hitlTimeoutSeconds = 300;
    /** 模型上下文窗口；MiniMax M3.1 为 1,000,000。 */
    private int contextWindow = 1_000_000;
    /** 单次输出上限（含思考 token）。 */
    private int maxOutputTokens = 4096;
    /** 技能目录：正文放本地文件，数据库只存索引。 */
    private String skillDir = "./skills";
    /** agentscope 会话状态表所在 schema/table，与 Flyway 迁移保持一致。 */
    private String stateSchema = "agentscope";
    private String stateTable = "agentscope_sessions";
    /** 与开放平台同一把 AES-GCM 密钥：用于加密 Agent 的模型 API Key。 */
    private String secretKey = System.getenv().getOrDefault("BASEPLATE_SECRET_KEY",
            "local-dev-only-change-me-secret-key");

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public void setDefaultBaseUrl(String defaultBaseUrl) {
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public String getDefaultApiKey() {
        return defaultApiKey;
    }

    public void setDefaultApiKey(String defaultApiKey) {
        this.defaultApiKey = defaultApiKey;
    }

    public int getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(int maxIters) {
        this.maxIters = maxIters;
    }

    public ToolExposureMode getToolExposureMode() {
        return toolExposureMode;
    }

    public void setToolExposureMode(ToolExposureMode toolExposureMode) {
        this.toolExposureMode = toolExposureMode;
    }

    public boolean isToolExposureSkill() {
        return toolExposureSkill;
    }

    public void setToolExposureSkill(boolean toolExposureSkill) {
        this.toolExposureSkill = toolExposureSkill;
    }

    public long getStreamTimeoutSeconds() {
        return streamTimeoutSeconds;
    }

    public void setStreamTimeoutSeconds(long streamTimeoutSeconds) {
        this.streamTimeoutSeconds = streamTimeoutSeconds;
    }

    public long getHitlTimeoutSeconds() {
        return hitlTimeoutSeconds;
    }

    public void setHitlTimeoutSeconds(long hitlTimeoutSeconds) {
        this.hitlTimeoutSeconds = hitlTimeoutSeconds;
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public String getSkillDir() {
        return skillDir;
    }

    public void setSkillDir(String skillDir) {
        this.skillDir = skillDir;
    }

    public String getStateSchema() {
        return stateSchema;
    }

    public void setStateSchema(String stateSchema) {
        this.stateSchema = stateSchema;
    }

    public String getStateTable() {
        return stateTable;
    }

    public void setStateTable(String stateTable) {
        this.stateTable = stateTable;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }
}
