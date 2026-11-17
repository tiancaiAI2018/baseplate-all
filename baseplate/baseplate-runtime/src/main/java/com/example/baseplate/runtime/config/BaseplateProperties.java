package com.example.baseplate.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** baseplate.* 配置：所有开关集中在此，模板由 starter 提供并做双向守卫。 */
@ConfigurationProperties(prefix = "baseplate")
public class BaseplateProperties {

    private boolean registryFailFast = true;
    private String migrationPlanPath = "build/migration-plan.json";
    private final Tenant tenant = new Tenant();
    private final Redis redis = new Redis();

    public boolean isRegistryFailFast() {
        return registryFailFast;
    }

    public void setRegistryFailFast(boolean registryFailFast) {
        this.registryFailFast = registryFailFast;
    }

    public String getMigrationPlanPath() {
        return migrationPlanPath;
    }

    public void setMigrationPlanPath(String migrationPlanPath) {
        this.migrationPlanPath = migrationPlanPath;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public Redis getRedis() {
        return redis;
    }

    public static class Tenant {
        /** shared-column 或 routing-datasource（预留扩展点）。 */
        private String mode = "shared-column";

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    public static class Redis {
        private boolean enabled = true;
        private boolean fallbackToMemory = true;
        private String keyPrefix = "bp:";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isFallbackToMemory() {
            return fallbackToMemory;
        }

        public void setFallbackToMemory(boolean fallbackToMemory) {
            this.fallbackToMemory = fallbackToMemory;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }
}
