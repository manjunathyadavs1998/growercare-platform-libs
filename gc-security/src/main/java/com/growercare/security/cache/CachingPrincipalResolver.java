package com.growercare.security.cache;

import com.growercare.security.GcPrincipal;
import com.growercare.security.PrincipalResolver;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache-aside in front of a remote {@link PrincipalResolver} (ADR-011: Redis is never the source of truth).
 * <ul>
 *   <li>hit → cached principal (grants still re-checked for expiry at use time);</li>
 *   <li>miss → delegate (IAM), then cache for {@code ttl}; "no account" is not cached;</li>
 *   <li>Redis failure → WARN and go straight to the delegate (degraded, not broken);</li>
 *   <li>delegate failure → propagates (the security layer answers 503 — fail closed).</li>
 * </ul>
 */
public final class CachingPrincipalResolver implements PrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(CachingPrincipalResolver.class);
    static final String KEY_PREFIX = "gc:principal:";

    private final PrincipalResolver delegate;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;

    public CachingPrincipalResolver(PrincipalResolver delegate, StringRedisTemplate redis, JsonMapper json, Duration ttl) {
        this.delegate = delegate;
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    @Override
    public Optional<GcPrincipal> resolve(UUID subject) {
        var key = KEY_PREFIX + subject;
        var cached = read(key);
        if (cached.isPresent()) {
            return cached;
        }
        var resolved = delegate.resolve(subject);
        resolved.ifPresent(principal -> write(key, principal));
        return resolved;
    }

    /** Called when IAM publishes a grant change for this subject (event consumer, later increments). */
    public void evict(UUID subject) {
        try {
            redis.delete(KEY_PREFIX + subject);
        } catch (RuntimeException e) {
            log.warn("Redis evict failed for a principal: {}", e.getMessage());
        }
    }

    private Optional<GcPrincipal> read(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key)).map(v -> json.readValue(v, GcPrincipal.class));
        } catch (RuntimeException e) {
            log.warn("Principal cache read failed, falling back to IAM: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void write(String key, GcPrincipal principal) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(principal), ttl);
        } catch (RuntimeException e) {
            log.warn("Principal cache write failed: {}", e.getMessage());
        }
    }
}
