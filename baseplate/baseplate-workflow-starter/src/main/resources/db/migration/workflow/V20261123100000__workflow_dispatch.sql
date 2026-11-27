-- 分单（派单）能力：池、组织范围、签到、坐席状态、策略、池任务、决策日志、拒绝、惩罚、评价
-- 全部带 tenant_id；组织是硬边界，池没配组织就不允许派单。

CREATE TABLE IF NOT EXISTS wf_work_pool (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    code          VARCHAR(64) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(255),
    status        VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    claim_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by    VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_wf_work_pool UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS wf_work_pool_org (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL,
    pool_id          BIGINT NOT NULL,
    org_id           BIGINT NOT NULL,
    include_children BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_wf_pool_org UNIQUE (tenant_id, pool_id, org_id)
);
CREATE INDEX IF NOT EXISTS idx_wf_pool_org_pool ON wf_work_pool_org(tenant_id, pool_id);

CREATE TABLE IF NOT EXISTS wf_checkin (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    biz_date    DATE NOT NULL,
    checkin_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    checkout_at TIMESTAMPTZ,
    source      VARCHAR(32) NOT NULL DEFAULT 'WEB',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_wf_checkin UNIQUE (tenant_id, user_id, biz_date)
);
CREATE INDEX IF NOT EXISTS idx_wf_checkin_date ON wf_checkin(tenant_id, biz_date);

CREATE TABLE IF NOT EXISTS wf_agent_state (
    tenant_id           VARCHAR(64) NOT NULL,
    user_id             VARCHAR(64) NOT NULL,
    online_status       VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
    last_heartbeat      TIMESTAMPTZ,
    skills              JSONB NOT NULL DEFAULT '[]',
    capacity            INT NOT NULL DEFAULT 10,
    weight              NUMERIC(6,2) NOT NULL DEFAULT 1.00,
    rating              NUMERIC(4,2) NOT NULL DEFAULT 5.00,
    rating_count        INT NOT NULL DEFAULT 0,
    consecutive_refusals INT NOT NULL DEFAULT 0,
    reject_count        INT NOT NULL DEFAULT 0,
    cooldown_until      TIMESTAMPTZ,
    last_refusal_at     TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE IF NOT EXISTS wf_dispatch_policy (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          VARCHAR(64) NOT NULL,
    process_key        VARCHAR(64) NOT NULL,
    definition_version INT NOT NULL DEFAULT 0,
    node_id            VARCHAR(64) NOT NULL,
    pool_id            BIGINT NOT NULL,
    mode               VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    algorithm          VARCHAR(32) NOT NULL DEFAULT 'LEAST_LOAD',
    policy_json        JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by         VARCHAR(64),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_wf_dispatch_policy UNIQUE (tenant_id, process_key, definition_version, node_id)
);

CREATE TABLE IF NOT EXISTS wf_pool_task (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    pool_id     BIGINT NOT NULL,
    task_id     VARCHAR(64) NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    node_id     VARCHAR(64) NOT NULL,
    requester_id VARCHAR(64),
    priority    INT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    claimed_by  VARCHAR(64),
    claimed_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_wf_pool_task UNIQUE (tenant_id, task_id)
);
CREATE INDEX IF NOT EXISTS idx_wf_pool_task_open
    ON wf_pool_task(tenant_id, pool_id, status, priority DESC, created_at);

CREATE TABLE IF NOT EXISTS wf_dispatch_log (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL,
    instance_id    VARCHAR(64) NOT NULL,
    task_id        VARCHAR(64),
    node_id        VARCHAR(64) NOT NULL,
    pool_id        BIGINT,
    algorithm      VARCHAR(32),
    candidates     JSONB,
    scores         JSONB,
    chosen_user_id VARCHAR(64),
    outcome        VARCHAR(32),
    reason         VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wf_dispatch_log_task ON wf_dispatch_log(tenant_id, task_id);
CREATE INDEX IF NOT EXISTS idx_wf_dispatch_log_pool ON wf_dispatch_log(tenant_id, pool_id, created_at DESC);

CREATE TABLE IF NOT EXISTS wf_dispatch_refusal (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    pool_id       BIGINT NOT NULL,
    task_id       VARCHAR(64) NOT NULL,
    instance_id   VARCHAR(64) NOT NULL,
    user_id       VARCHAR(64) NOT NULL,
    refusal_type  VARCHAR(24) NOT NULL,
    reason_code   VARCHAR(32) NOT NULL,
    reason_text   VARCHAR(255),
    policy_snapshot JSONB,
    effect_json   JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wf_refusal_user ON wf_dispatch_refusal(tenant_id, user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wf_refusal_task ON wf_dispatch_refusal(tenant_id, task_id, user_id);
CREATE INDEX IF NOT EXISTS idx_wf_refusal_pool ON wf_dispatch_refusal(tenant_id, pool_id, created_at DESC);

CREATE TABLE IF NOT EXISTS wf_agent_penalty (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    user_id           VARCHAR(64) NOT NULL,
    pool_id           BIGINT,
    penalty_type      VARCHAR(24) NOT NULL,
    value             NUMERIC(10,2),
    reason            VARCHAR(255),
    source_refusal_id BIGINT,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    revoked_by        VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wf_penalty_active
    ON wf_agent_penalty(tenant_id, user_id, pool_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS wf_rating (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL,
    instance_id    VARCHAR(64) NOT NULL,
    task_id        VARCHAR(64) NOT NULL,
    pool_id        BIGINT,
    target_user_id VARCHAR(64) NOT NULL,
    score          INT NOT NULL,
    tags           JSONB NOT NULL DEFAULT '[]'::jsonb,
    comment        VARCHAR(500),
    rater_id       VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_wf_rating UNIQUE (tenant_id, task_id, rater_id),
    CONSTRAINT ck_wf_rating_score CHECK (score BETWEEN 1 AND 5)
);
CREATE INDEX IF NOT EXISTS idx_wf_rating_target ON wf_rating(tenant_id, target_user_id, created_at DESC);
