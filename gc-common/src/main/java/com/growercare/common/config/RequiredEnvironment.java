package com.growercare.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * SPEC-PLAT-06 R76 (ADR-017): a service refuses to start while any required setting is missing, and names
 * <b>all</b> missing environment variables in one message (never their values).
 * <p>
 * A service lists the properties it needs in {@code gc.required-properties}; each is normally {@code ${ENV_VAR}}
 * without a default in {@code application.yml}. Runs as a {@link BeanFactoryPostProcessor}, i.e. after every
 * property source (environment, {@code .env}, test properties) is in place and before any bean is created.
 */
public final class RequiredEnvironment implements BeanFactoryPostProcessor {

    public static final String PROPERTY = "gc.required-properties";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final ConfigurableEnvironment environment;

    public RequiredEnvironment(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        var missing = missing(environment);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required configuration: " + String.join(", ", missing)
                    + ". Set these environment variables (documented in .env.example) and start again.");
        }
    }

    /** @return one entry per missing setting: {@code ENV_VAR (property)}, or the property name when it has no placeholder */
    public static List<String> missing(ConfigurableEnvironment environment) {
        var required = environment.getProperty(PROPERTY, String[].class, new String[0]);
        var missing = new ArrayList<String>();
        for (var property : required) {
            var name = property.strip();
            if (name.isEmpty()) {
                continue;
            }
            String value;
            try {
                value = environment.getProperty(name);
            } catch (IllegalArgumentException unresolvable) {               // ${VAR} without a value
                value = null;
            }
            if (value == null || value.isBlank()) {
                missing.add(describe(environment, name));
            }
        }
        return List.copyOf(missing);
    }

    /** Names the environment variable behind a property ({@code DB_URL (spring.datasource.url)}). */
    private static String describe(ConfigurableEnvironment environment, String property) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source.getProperty(property) instanceof String raw) {
                var matcher = PLACEHOLDER.matcher(raw);
                if (matcher.find()) {
                    return matcher.group(1) + " (" + property + ")";
                }
            }
        }
        return property;
    }
}
