-- 智能体 ↔ 技能绑定：技能正文仍在本地 skills 目录，这张表只决定
-- 「哪个 Agent 能看到哪些技能」。没有绑定记录视为未配置，默认全部可用。
CREATE TABLE IF NOT EXISTS agent_definition_skill (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    agent_id            BIGINT       NOT NULL REFERENCES agent_definition (id) ON DELETE CASCADE,
    skill_name          VARCHAR(128) NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_definition_skill UNIQUE (agent_id, skill_name)
);
CREATE INDEX IF NOT EXISTS idx_agent_definition_skill
    ON agent_definition_skill (tenant_id, agent_id);
