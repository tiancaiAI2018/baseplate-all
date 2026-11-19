-- 智能体模块：配置、工具策略、技能索引、会话与消息、运行观测。
-- 约定与基盘一致：tenant_id 全表存在（共享列租户模式），JSONB 存可变结构，时间统一 timestamptz。

-- ============ 智能体定义 ============
CREATE TABLE IF NOT EXISTS agent_definition (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    agent_code          VARCHAR(64)  NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    -- 模型厂商：OPENAI_COMPATIBLE / DASHSCOPE / ANTHROPIC / GEMINI / OLLAMA
    provider            VARCHAR(32)  NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
    model_name          VARCHAR(128) NOT NULL,
    base_url            VARCHAR(512),
    -- API Key 只存密文，使用 BASEPLATE_SECRET_KEY 做 AES-GCM 加密
    api_key_enc         TEXT,
    system_prompt       TEXT,
    -- 推理参数：temperature / maxOutputTokens / contextWindow / reasoningEffort
    model_params        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- 允许使用该 Agent 的页面 key 列表，如 ["workflow/designer","dashboard"]；["*"] 表示全部
    allowed_pages       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    -- 配置版本：运行时会话按版本失效，避免旧 Agent 实例继续使用旧提示词
    revision            INT          NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_definition UNIQUE (tenant_id, agent_code)
);
CREATE INDEX IF NOT EXISTS idx_agent_definition_tenant ON agent_definition (tenant_id, status);

-- ============ 工具目录（内置工具由启动期同步，只读） ============
CREATE TABLE IF NOT EXISTS agent_tool_definition (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    tool_code           VARCHAR(128) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    -- BUILTIN_INTERNAL / CUSTOM_INTERNAL / EXTERNAL
    tool_type           VARCHAR(24)  NOT NULL,
    builtin             BOOLEAN      NOT NULL DEFAULT FALSE,
    editable            BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 允许使用该工具的页面；空数组表示不限制页面（仍需 Agent 页面绑定通过）
    pages               JSONB        NOT NULL DEFAULT '[]'::jsonb,
    permission_code     VARCHAR(64),
    -- READ / WRITE / DESTRUCTIVE
    risk_level          VARCHAR(16)  NOT NULL DEFAULT 'WRITE',
    -- 安全下限：DESTRUCTIVE 至少 ASK，配置只能更严不能更松
    min_approval        VARCHAR(16)  NOT NULL DEFAULT 'ALLOW',
    default_approval    VARCHAR(16)  NOT NULL DEFAULT 'ALLOW',
    params_schema       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    config              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status              VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_tool_definition UNIQUE (tenant_id, tool_code)
);

-- ============ Agent ↔ 工具绑定（启停 + HITL 策略） ============
CREATE TABLE IF NOT EXISTS agent_definition_tool (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    agent_id            BIGINT       NOT NULL REFERENCES agent_definition (id) ON DELETE CASCADE,
    tool_code           VARCHAR(128) NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    -- INHERIT / ALLOW / ASK / DENY
    approval_mode       VARCHAR(16)  NOT NULL DEFAULT 'INHERIT',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_definition_tool UNIQUE (agent_id, tool_code)
);

-- ============ 技能索引（正文在本地文件，库里只存索引与有效性） ============
CREATE TABLE IF NOT EXISTS agent_skill_index (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    skill_name          VARCHAR(128) NOT NULL,
    description         TEXT,
    path                VARCHAR(512) NOT NULL,
    version             VARCHAR(32),
    checksum            VARCHAR(64),
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    -- VALID / INVALID / MISSING
    status              VARCHAR(16)  NOT NULL DEFAULT 'VALID',
    last_scan_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_skill_index UNIQUE (tenant_id, skill_name)
);

-- ============ 会话与消息 ============
CREATE TABLE IF NOT EXISTS agent_session (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    agent_id            BIGINT       NOT NULL REFERENCES agent_definition (id),
    user_id             VARCHAR(64)  NOT NULL,
    page_key            VARCHAR(128),
    title               VARCHAR(200),
    message_count       INT          NOT NULL DEFAULT 0,
    last_active_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_session_owner
    ON agent_session (tenant_id, user_id, last_active_at DESC) WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS agent_message (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    session_id          BIGINT       NOT NULL REFERENCES agent_session (id) ON DELETE CASCADE,
    seq                 INT          NOT NULL,
    role                VARCHAR(16)  NOT NULL,
    content             TEXT,
    -- 按顺序回放的事件块：text/thinking/tool_call/tool_result/plan/skill/hitl
    blocks              JSONB        NOT NULL DEFAULT '[]'::jsonb,
    model               VARCHAR(128),
    usage               JSONB        NOT NULL DEFAULT '{}'::jsonb,
    latency_ms          INT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_message_seq UNIQUE (session_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_agent_message_session ON agent_message (session_id, seq);

-- ============ 运行观测 ============
CREATE TABLE IF NOT EXISTS agent_run (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    session_id          BIGINT       NOT NULL REFERENCES agent_session (id) ON DELETE CASCADE,
    message_id          BIGINT,
    trace_id            VARCHAR(64),
    model               VARCHAR(128),
    input_tokens        INT          NOT NULL DEFAULT 0,
    output_tokens       INT          NOT NULL DEFAULT 0,
    cached_tokens       INT          NOT NULL DEFAULT 0,
    steps               INT          NOT NULL DEFAULT 0,
    tool_calls          INT          NOT NULL DEFAULT 0,
    latency_ms          INT,
    ttft_ms             INT,
    status              VARCHAR(16)  NOT NULL DEFAULT 'OK',
    error_code          VARCHAR(64),
    error_message       TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_agent_run_session ON agent_run (session_id, created_at DESC);

-- ============ agentscope 会话上下文状态表（与 PostgresAgentStateStore 结构一致） ============
CREATE SCHEMA IF NOT EXISTS agentscope;
CREATE TABLE IF NOT EXISTS agentscope.agentscope_sessions (
    session_id          VARCHAR(255) NOT NULL,
    state_key           VARCHAR(255) NOT NULL,
    item_index          INT          NOT NULL DEFAULT 0,
    state_data          TEXT         NOT NULL,
    version             BIGINT       NOT NULL DEFAULT 1,
    created_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
);
