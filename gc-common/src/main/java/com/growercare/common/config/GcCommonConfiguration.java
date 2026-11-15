package com.growercare.common.config;

import com.growercare.common.sql.NamedSqlRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared infrastructure beans for every service (picked up by the {@code com.growercare} component scan). */
@Configuration(proxyBeanMethods = false)
public class GcCommonConfiguration {

    /** UTC clock; tests replace it to control time (grant expiry, SLAs). */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Named SQL statements from {@code classpath*:sql/postgres/*.sql} (ADR-013). */
    @Bean
    @ConditionalOnMissingBean
    NamedSqlRegistry namedSqlRegistry() {
        return NamedSqlRegistry.load("postgres");
    }
}
