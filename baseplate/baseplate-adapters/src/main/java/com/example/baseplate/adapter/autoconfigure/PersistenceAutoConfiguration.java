package com.example.baseplate.adapter.autoconfigure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.baseplate.adapter.out.persistence.mybatis.ModuleRegistryRepository;
import com.example.baseplate.adapter.out.persistence.mybatis.mapper.ModuleRegistryMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/** 持久化装配：有 DataSource 且有 MP 时才生效。 */
@AutoConfiguration
@ConditionalOnClass({BaseMapper.class, DataSource.class})
@ConditionalOnBean(DataSource.class)
public class PersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ModuleRegistryRepository moduleRegistryRepository(ModuleRegistryMapper mapper) {
        return new ModuleRegistryRepository(mapper);
    }
}
