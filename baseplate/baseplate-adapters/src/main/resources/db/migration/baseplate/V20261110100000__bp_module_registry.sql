-- 基盘第一张表：模块清单注册表
-- 规则：只有 enabled=true 的模块才注册 flyway-locations 并参与建表
CREATE TABLE IF NOT EXISTS bp_module_registry (
    module_id  VARCHAR(64)  PRIMARY KEY,
    version    VARCHAR(32)  NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    checksum   VARCHAR(64),
    loaded_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE bp_module_registry IS '模块清单注册表：启动期 upsert，enabled=false 的模块不建表、不注册路由';
COMMENT ON COLUMN bp_module_registry.checksum IS '清单文件内容指纹，用于检测清单变更';
