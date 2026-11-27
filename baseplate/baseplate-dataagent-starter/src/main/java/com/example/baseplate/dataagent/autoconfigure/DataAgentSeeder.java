package com.example.baseplate.dataagent.autoconfigure;

import com.example.baseplate.agent.adapter.out.persistence.AgentDefinitionRepository;
import com.example.baseplate.agent.config.AgentProperties;
import com.example.baseplate.agent.domain.AgentDefinition;
import com.example.baseplate.agent.domain.ApprovalMode;
import com.example.baseplate.agent.runtime.AgentRuntimeFactory;
import com.example.baseplate.agent.tool.AgentToolProvider;
import com.example.baseplate.dataagent.tool.DataAgentToolProvider;
import com.example.baseplate.runtime.crypto.AesGcmCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 启动期创建 data-agent（不存在时），并绑定本模块的工具。 */
public final class DataAgentSeeder implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DataAgentSeeder.class);
    private static final String TENANT = "default";
    private static final String CODE = "data-agent";
    private static final String PROMPT = """
            你是数据助手。用户用自然语言提问时，先通过工具发现表结构与业务口径，
            再用只读 SQL 查询数据；确认口径前不要编造统计结果。
            查询结果可以带图表卡片，正文只补结论，不复述大段数据。
            """;

    private final AgentDefinitionRepository repository;
    private final AgentProperties properties;
    private final AgentRuntimeFactory runtimeFactory;
    private final List<String> toolCodes;

    public DataAgentSeeder(AgentDefinitionRepository repository, AgentProperties properties,
                           AgentRuntimeFactory runtimeFactory, AgentToolProvider provider) {
        this.repository = repository;
        this.properties = properties;
        this.runtimeFactory = runtimeFactory;
        this.toolCodes = provider.tools().stream()
                .map(spec -> spec.descriptor().code())
                .filter(code -> code.startsWith("datasource.") || code.startsWith("semantic_model.")
                        || code.startsWith("domain_business_knowledge.") || code.startsWith("sql_guard."))
                .toList();
    }

    @Override
    public void afterSingletonsInstantiated() {
        AgentDefinition existing = repository.list(TENANT).stream()
                .filter(agent -> CODE.equals(agent.agentCode())).findFirst().orElse(null);
        if (existing != null) {
            Map<String, Object> params = new LinkedHashMap<>(
                    existing.modelParams() == null ? Map.of() : existing.modelParams());
            if (params.containsKey("maxIters")) {
                return;
            }
            params.put("maxIters", 10);
            repository.update(TENANT, existing.id(), new AgentDefinitionRepository.DefinitionInput(
                    CODE, existing.name(), existing.description(), existing.provider(),
                    existing.modelName(), existing.baseUrl(), existing.apiKeyEnc(),
                    existing.systemPrompt(), params, existing.allowedPages(), existing.status()));
            runtimeFactory.invalidateAll();
            log.info("已为 data-agent 补充 maxIters=10");
            return;
        }
        if (properties.getDefaultApiKey() == null || properties.getDefaultApiKey().isBlank()) {
            log.info("未配置 BASEPLATE_AGENT_API_KEY，跳过 data-agent 种子");
            return;
        }
        String encryptedKey = AesGcmCipher.encrypt(properties.getDefaultApiKey(),
                properties.getSecretKey());
        long id = repository.insert(TENANT, new AgentDefinitionRepository.DefinitionInput(
                CODE, "数据助手", "用自然语言查当前数据库，输出数据与图表。",
                "OPENAI_COMPATIBLE", properties.getDefaultModel(), properties.getDefaultBaseUrl(),
                encryptedKey, PROMPT,
                Map.of("contextWindow", properties.getContextWindow(),
                        "maxOutputTokens", properties.getMaxOutputTokens(),
                        "reasoningEffort", "low", "maxIters", 10),
                List.of("*"), "ENABLED"));
        for (String code : toolCodes) {
            repository.upsertToolBinding(TENANT, id, code, true, ApprovalMode.INHERIT);
        }
        runtimeFactory.invalidateAll();
        log.info("已创建 data-agent，绑定 {} 个数据工具", toolCodes.size());
    }
}
