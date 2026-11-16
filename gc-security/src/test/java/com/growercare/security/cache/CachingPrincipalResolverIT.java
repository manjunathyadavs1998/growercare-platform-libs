package com.growercare.security.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.security.EffectiveGrant;
import com.growercare.security.GcPrincipal;
import com.growercare.security.PiiClearance;
import com.growercare.security.PrincipalResolver;
import com.growercare.security.ScopeKind;
import com.growercare.security.UserType;
import com.growercare.testsupport.RedisTestContainer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** ADR-011: Redis caches principals, degrades gracefully, and never fails open. (Moved from case-service, ADR-017.) */
class CachingPrincipalResolverIT {

    static final GcPrincipal CARE = new GcPrincipal(101, UUID.fromString("6f1c0a10-0101-4a00-8000-000000000101"), UserType.STAFF,
            "IN", null, List.of(new EffectiveGrant(1L, "CARE_AGENT", 30, ScopeKind.COUNTRY, PiiClearance.MASKED,
                    Set.of("case:read"), Set.of("IN"), Set.of(), Set.of(), null)));

    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;

    final AtomicInteger iamCalls = new AtomicInteger();
    final Map<UUID, GcPrincipal> iam = Map.of(CARE.subject(), CARE);
    final PrincipalResolver fakeIam = subject -> {
        iamCalls.incrementAndGet();
        return Optional.ofNullable(iam.get(subject));
    };
    CachingPrincipalResolver resolver;

    @BeforeAll
    static void redis() {
        var config = new RedisStandaloneConfiguration(RedisTestContainer.host(), RedisTestContainer.port());
        config.setPassword(RedisPassword.of(RedisTestContainer.PASSWORD));
        connections = new LettuceConnectionFactory(config,
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
    }

    @AfterAll
    static void close() {
        connections.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.delete(redis.keys("gc:principal:*"));
        resolver = new CachingPrincipalResolver(fakeIam, redis, JsonMapper.builder().build(), Duration.ofMinutes(5));
    }

    @Test
    void secondLookupIsServedFromRedisWithTtl() {
        assertThat(resolver.resolve(CARE.subject())).contains(CARE);
        assertThat(resolver.resolve(CARE.subject())).contains(CARE);
        assertThat(iamCalls).hasValue(1);
        assertThat(redis.getExpire(CachingPrincipalResolver.cacheKey(CARE.subject()))).isBetween(1L, 300L);
    }

    @Test
    void unknownAccountsAreNotCached() {
        var stranger = UUID.randomUUID();
        assertThat(resolver.resolve(stranger)).isEmpty();
        assertThat(resolver.resolve(stranger)).isEmpty();
        assertThat(iamCalls).hasValue(2);
    }

    @Test
    void evictForcesAFreshLookup() {
        resolver.resolve(CARE.subject());
        resolver.evict(CARE.subject());
        resolver.resolve(CARE.subject());
        assertThat(iamCalls).hasValue(2);
    }

    @Test
    void redisOutageFallsBackToIam() {
        RedisTestContainer.pause();
        try {
            assertThat(resolver.resolve(CARE.subject())).contains(CARE);
            resolver.evict(CARE.subject());                                   // best effort: logged, not thrown
            assertThat(iamCalls).hasValue(1);
        } finally {
            RedisTestContainer.unpause();
        }
    }

    @Test
    void iamOutageIsNeverMaskedAsAnonymous() {
        var failing = new CachingPrincipalResolver(subject -> {
            throw new DependencyUnavailableException("identity-access-service", null);
        }, redis, JsonMapper.builder().build(), Duration.ofMinutes(5));
        assertThatThrownBy(() -> failing.resolve(UUID.randomUUID())).isInstanceOf(DependencyUnavailableException.class);
    }
}
