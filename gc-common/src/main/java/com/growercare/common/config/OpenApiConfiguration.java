package com.growercare.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SPEC-PLAT-01 R1: OpenAPI description shared by all services — title from {@code spring.application.name} and a
 * global HTTP bearer (JWT) security scheme. Exposure is controlled per profile with {@code springdoc.api-docs.enabled}
 * (off by default, on in {@code local}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.swagger.v3.oas.models.OpenAPI")
public class OpenApiConfiguration {

    public static final String BEARER = "bearer";

    @Bean
    OpenAPI growerCareOpenApi(@Value("${spring.application.name}") String service) {
        return new OpenAPI()
                .info(new Info()
                        .title(service)
                        .version("v1")
                        .description("GrowerCare " + service + ". Errors are RFC 9457 problem+json; IDs are strings."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Keycloak access token (audience gc-api)")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
