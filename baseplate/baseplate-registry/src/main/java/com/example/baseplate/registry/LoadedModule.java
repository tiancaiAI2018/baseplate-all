package com.example.baseplate.registry;

import com.example.baseplate.api.model.ModuleDescriptor;

/** 清单加载结果：保留来源，便于启动日志与排障。 */
public record LoadedModule(ModuleDescriptor descriptor, String source, boolean enabled) {
}
