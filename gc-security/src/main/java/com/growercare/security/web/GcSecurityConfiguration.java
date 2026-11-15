package com.growercare.security.web;

import com.growercare.security.PolicyEnforcer;
import com.growercare.security.PrincipalResolver;
import com.growercare.security.ScopeFilter;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security for every GrowerCare MVC service (picked up by the {@code com.growercare} component scan).
 * Each service supplies one {@link PrincipalResolver} bean; everything else is shared:
 * <ul>
 *   <li>stateless OAuth2 resource server (Keycloak JWT; issuer/audience via {@code spring.security.oauth2.resourceserver.jwt.*});</li>
 *   <li>{@code /internal/**} only for allow-listed service clients; all other routes only for resolved end users;</li>
 *   <li>method security with {@code @RequiresPermission};</li>
 *   <li>401/403/503 as Problem Details; strict CORS; API security headers.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(GcSecurityProperties.class)
public class GcSecurityConfiguration {

    @Bean
    SecurityFilterChain gcSecurityFilterChain(HttpSecurity http, GcJwtAuthenticationConverter converter,
                                              ProblemSecurityHandlers problems,
                                              @Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) throws Exception {
        http
                .csrf(csrf -> csrf.disable())                       // no cookies/sessions: bearer tokens only
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(Customizer.withDefaults())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(c -> c.disable())
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(f -> f.deny()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()   // scraped in-cluster; blocked at the gateway
                        .requestMatchers("/error").permitAll()
                        // SPEC-PLAT-01 D1/R2: docs are public only where they are enabled (local profile)
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .access((authentication, context) -> new org.springframework.security.authorization.AuthorizationDecision(apiDocsEnabled))
                        .requestMatchers("/internal/**").hasAuthority(ServiceAuthenticationToken.ROLE_SERVICE)
                        .anyRequest().hasAuthority(GcJwtAuthenticationConverter.ROLE_USER))
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems));
        return http.build();
    }

    @Bean
    GcJwtAuthenticationConverter gcJwtAuthenticationConverter(PrincipalResolver resolver, GcSecurityProperties props, Clock clock) {
        return new GcJwtAuthenticationConverter(resolver, props, clock);
    }

    @Bean
    ProblemSecurityHandlers problemSecurityHandlers(JsonMapper json) {
        return new ProblemSecurityHandlers(json);
    }

    /** Enables {@code {value}} placeholders in {@link com.growercare.security.RequiresPermission}. */
    @Bean
    static AnnotationTemplateExpressionDefaults annotationTemplateExpressionDefaults() {
        return new AnnotationTemplateExpressionDefaults();
    }

    @Bean("gcAuthz")
    GcAuthorization gcAuthz(Clock clock) {
        return new GcAuthorization(clock);
    }

    @Bean
    PolicyEnforcer policyEnforcer(Clock clock) {
        return new PolicyEnforcer(clock);
    }

    @Bean
    ScopeFilter scopeFilter(Clock clock) {
        return new ScopeFilter(clock);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GcSecurityProperties props) {
        var source = new UrlBasedCorsConfigurationSource();
        if (!props.corsAllowedOrigins().isEmpty()) {
            var cors = new CorsConfiguration();
            cors.setAllowedOrigins(props.corsAllowedOrigins());
            cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key", "traceparent"));
            cors.setExposedHeaders(List.of("ETag", "Location"));
            cors.setAllowCredentials(false);
            cors.setMaxAge(3600L);
            source.registerCorsConfiguration("/**", cors);
        }
        return source;
    }
}
