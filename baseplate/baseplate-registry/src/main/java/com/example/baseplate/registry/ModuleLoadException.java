package com.example.baseplate.registry;

/** 清单加载或校验失败：启动期 fail-fast。 */
public class ModuleLoadException extends RuntimeException {
    public ModuleLoadException(String message) {
        super(message);
    }

    public ModuleLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
