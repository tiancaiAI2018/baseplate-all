-- 外部服务/端点元数据落库：代码种子只作为首次初始化，之后由控制台 CRUD 维护。
-- 代理网关仍从 registry 读取（构造期加载本表），因此改完立即 reload 即可生效。

CREATE TABLE IF NOT EXISTS platform_external_service (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'default',
    code              VARCHAR(64)  NOT NULL,
    name              VARCHAR(128),
    base_url          VARCHAR(512),
    auth_type         VARCHAR(32),
    credential_header VARCHAR(64),
    credential_enc    TEXT,
    static_headers    JSONB,
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_platform_external_service UNIQUE (tenant_id, code)
);

CREATE TABLE IF NOT EXISTS platform_external_endpoint (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'default',
    service_code     VARCHAR(64)  NOT NULL,
    code             VARCHAR(64)  NOT NULL,
    path             VARCHAR(256) NOT NULL,
    method           VARCHAR(16)  NOT NULL DEFAULT 'GET',
    readonly         BOOLEAN      NOT NULL DEFAULT true,
    tool_code        VARCHAR(64),
    tool_name        VARCHAR(128),
    tool_description TEXT,
    params_schema    JSONB,
    pages            JSONB,
    permission_code  VARCHAR(128),
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    sort_no          INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_platform_external_endpoint UNIQUE (tenant_id, service_code, code),
    CONSTRAINT fk_platform_external_endpoint_service
        FOREIGN KEY (tenant_id, service_code)
        REFERENCES platform_external_service (tenant_id, code) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_platform_external_endpoint_service
    ON platform_external_endpoint (tenant_id, service_code, sort_no);

-- 凭证密文无法在 SQL 里计算：种子阶段写占位符，启动时解密失败会被 registry 容错处理，
-- 后续通过管理接口写入时用 SecretCipher 加密真实凭证。
INSERT INTO platform_external_service
    (tenant_id, code, name, base_url, auth_type, credential_header, credential_enc, static_headers, status)
VALUES ('default', 'loan-mock', '车贷模拟服务', 'http://localhost:8099',
        'API_KEY', 'X-Api-Key', 'SQL_SEED_PLACEHOLDER', '{}'::jsonb, 'ACTIVE')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO platform_external_endpoint
    (tenant_id, service_code, code, path, method, readonly, tool_code, tool_name,
     tool_description, params_schema, pages, permission_code, status, sort_no)
VALUES
    ('default', 'loan-mock', 'draft.prepare', '/api/draft/prepare', 'POST', true,
     'loan.draft.prepare', '抽取申请草稿骨架',
     '按资料 id 确定性抽取申请草稿骨架：字典编码已填好，返回 pendingFields 与 conflicts；先调它再补字段',
     '{"type":"object","properties":{"materialIds":{"type":"array","items":{"type":"string"},"description":"待分析资料 id 列表，如 MAT-XXXX"},"productType":{"type":"string","description":"产品编码，可不传由服务端推断"}},"required":["materialIds"]}'::jsonb,
     '["*"]'::jsonb, 'agent:chat', 'ACTIVE', 1),
    ('default', 'loan-mock', 'products', '/api/meta/products', 'GET', true,
     'loan.products', '贷款产品目录', '列出合法的产品类型编码与规则；必须先查它再选 productType',
     '{"type":"object","properties":{},"required":[]}'::jsonb,
     '["*"]'::jsonb, 'agent:chat', 'ACTIVE', 2),
    ('default', 'loan-mock', 'schema', '/api/meta/form-schema', 'GET', true,
     'loan.schema', '表单字段 Schema', '按产品编码取字段清单、字典与联动规则',
     '{"type":"object","properties":{"productType":{"type":"string","description":"产品编码"}},"required":["productType"]}'::jsonb,
     '["*"]'::jsonb, 'agent:chat', 'ACTIVE', 3),
    ('default', 'loan-mock', 'materials.list', '/api/materials', 'GET', true,
     'loan.materials.list', '待分析资料列表', '列出用户从微信/相册上传的待分析资料',
     '{"type":"object","properties":{},"required":[]}'::jsonb,
     '["*"]'::jsonb, 'agent:chat', 'ACTIVE', 4),
    ('default', 'loan-mock', 'materials.get', '/api/materials/{id}', 'GET', true,
     'loan.materials.get', '待分析资料详情', '按 id 读取一份待分析资料的文本与图片信息',
     '{"type":"object","properties":{"id":{"type":"string","description":"待分析资料 id"}},"required":["id"]}'::jsonb,
     '["*"]'::jsonb, 'agent:chat', 'ACTIVE', 5),
    ('default', 'loan-mock', 'catalog', '/api/meta/vehicle-tree', 'GET', true,
     'loan.catalog', '车贷车型目录', '按车辆类别查询品牌/车系/车型/年款树',
     '{"type":"object","properties":{"category":{"type":"string","description":"车辆类别，如 NEW_ENERGY"}},"required":[]}'::jsonb,
     '["*"]'::jsonb, 'agent:chat', 'ACTIVE', 6),
    ('default', 'loan-mock', 'validate', '/api/validate', 'POST', true,
     'loan.validate', '车贷草稿预检', '校验车贷草稿，返回字段级错误；不写数据',
     '{"type":"object","properties":{"draft":{"type":"object","description":"完整申请草稿 JSON"}},"required":["draft"]}'::jsonb,
     '["*"]'::jsonb, 'agent:chat', 'ACTIVE', 7),
    ('default', 'loan-mock', 'draft.save', '/api/drafts', 'POST', false,
     'loan.draft.save', '保存车贷草稿', '把车贷申请草稿保存到第三方服务（需用户确认）',
     '{"type":"object","properties":{"draft":{"type":"object","description":"完整申请草稿 JSON"}},"required":["draft"]}'::jsonb,
     '["*"]'::jsonb, 'agent:chat', 'ACTIVE', 8),
    ('default', 'loan-mock', 'submit', '/api/drafts/{draftId}/submit', 'POST', false,
     'loan.submit', '提交车贷申请', '最终提交车贷申请，必须带幂等键（需用户确认）',
     '{"type":"object","properties":{"draftId":{"type":"string","description":"草稿 id"},"idempotencyKey":{"type":"string","description":"幂等键"}},"required":["draftId"]}'::jsonb,
     '["*"]'::jsonb, 'agent:chat', 'ACTIVE', 9)
ON CONFLICT (tenant_id, service_code, code) DO NOTHING;

COMMENT ON TABLE platform_external_service IS '平台注册的外部服务（凭证只存密文）';
COMMENT ON TABLE platform_external_endpoint IS '外部服务暴露的端点及其 Agent 工具投影元数据';
