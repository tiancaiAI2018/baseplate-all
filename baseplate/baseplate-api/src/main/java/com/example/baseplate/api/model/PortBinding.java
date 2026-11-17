package com.example.baseplate.api.model;

import java.util.Set;

/** 端口注册表的一行：/actuator/ports 的数据源。 */
public record PortBinding(String portName, String implClass, String mode, Set<Capability> capabilities) {
}
