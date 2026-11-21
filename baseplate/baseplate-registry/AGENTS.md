# BASEPLATE REGISTRY

## OVERVIEW
模块清单（`META-INF/baseplate-module.yml`）的解析、校验、持久化与迁移计划生成。启动期 fail-fast 的来源。

## STRUCTURE
```
com.example.baseplate.registry/
├── ModuleDescriptorLoader.java     # 扫描 classpath 下清单
├── ModuleDescriptorValidator.java  # 字段/id/depends-on/flyway 路径校验
├── ModuleRegistryImpl.java         # 内存索引
├── MigrationPlanWriter.java        # build/migration-plan.json
├── StartupVerifier.java            # 启动期自检
├── LoadedModule.java、ModuleLoadException.java
└── autoconfigure/ModuleRegistryAutoConfiguration.java
```

## CONVENTIONS
- `baseplate.registry-fail-fast=true` 默认；清单任何不合规都让启动失败，把问题留在启动期。
- `id` 全局唯一；`depends-on` 必须指向真实模块；`flyway-locations` 目录必须存在。
- 权限点只能停用不能删除；删除会在下次启动被清单重新写回。
- 迁移计划路径来自 `baseplate.migration-plan-path`。

## ANTI-PATTERNS
- 为启动成功关掉 fail-fast。
- 手工改 `build/migration-plan.json`（生成物）。
- 清单字段改名不同步 `baseplate-api` 的 `ModuleDescriptor` 与文档。
