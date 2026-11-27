package com.example.baseplate.agent.application;

import com.example.baseplate.agent.config.AgentProperties;
import com.example.baseplate.agent.domain.AgentDefinition;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 只验证平台配置到官方 CompactionConfig 的映射，不触发任何模型调用。 */
class AgentCompactionConfigTest {

    @Test
    void automaticUsesPercentThresholdAndAgentWindow() {
        AgentProperties properties = new AgentProperties();
        properties.setCompactionThreshold(80);
        properties.setCompactionKeepMessages(15);
        properties.setContextWindow(1_000_000);

        CompactionConfig config = AgentCompactionConfig.automatic(
                properties, agent(Map.of("contextWindow", 200_000)));

        assertEquals(160_000, config.getTriggerTokens());
        assertEquals(50, config.getTriggerMessages());
        assertEquals(15, config.getKeepMessages());
        assertEquals(0, config.getKeepTokens());
        assertFalse(config.isFlushBeforeCompact());
        assertFalse(config.isOffloadBeforeCompact());
    }

    @Test
    void manualForcesTriggerAndClampsKeepMessages() {
        AgentProperties properties = new AgentProperties();
        properties.setCompactionThreshold(80);
        properties.setCompactionKeepMessages(20);

        CompactionConfig config = AgentCompactionConfig.manual(properties, 500);
        assertEquals(1, config.getTriggerMessages());
        assertEquals(200, config.getKeepMessages());

        config = AgentCompactionConfig.manual(properties, 1);
        assertEquals(2, config.getKeepMessages());
    }

    @Test
    void contextWindowFallsBackToGlobalConfig() {
        AgentProperties properties = new AgentProperties();
        properties.setContextWindow(64_000);
        assertEquals(64_000, AgentCompactionConfig.contextWindow(properties, agent(Map.of())));
    }

    private static AgentDefinition agent(Map<String, Object> modelParams) {
        return new AgentDefinition(1L, "default", "demo", "Demo", "", "openai", "model",
                "http://localhost", "", "", modelParams, List.of("*"), "ENABLED", 1);
    }
}
