package com.example.baseplate.adapter.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：只做 Mapper 扫描。
 * 刻意不注册 DataPermissionInterceptor——数据权限走显式 DataScope 下传，
 * 避免把业务规则藏进 ORM，换 ORM 时失效。
 */
@Configuration
@MapperScan("com.example.baseplate.adapter.out.persistence.mybatis.mapper")
public class MybatisPlusConfig {
}
