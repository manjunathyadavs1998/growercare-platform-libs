package com.growercare.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class ShardRouterTest {

    private static final Country IN = new Country("IN", 46, 71, "APAC", ZoneId.of("Asia/Kolkata"));
    private static final Country BR = new Country("BR", 8, 31, "LATAM", ZoneId.of("America/Sao_Paulo"));
    private final ShardRouter router = new ShardRouter(new CountryRegistry(List.of(IN, BR)));

    @Test
    void growerRoutingIsDeterministicAndEvenlySpread() {
        var counts = LongStream.rangeClosed(1, 64_000)
                .mapToObj(g -> router.forGrower("IN", g))
                .collect(Collectors.groupingBy(ShardId::shardNo, Collectors.counting()));

        assertThat(counts).hasSize(64);
        // Mean is 1,000 per shard; sequential grower IDs must not cluster.
        assertThat(counts.values()).allSatisfy(c -> assertThat(c).isBetween(850L, 1150L));
        assertThat(router.forGrower("IN", 70001)).isEqualTo(router.forGrower("IN", 70001));
    }

    @Test
    void routesIdsAndCaseNumbersWithoutLookups() {
        long id = IdCodec.encode(Instant.parse("2026-11-15T00:00:00Z"), 46, 27, 5);
        assertThat(router.forId(id)).contains(new ShardId("IN", 27));
        assertThat(router.forCaseNumber(CaseNumber.parse("71-0318-27-482139-6").orElseThrow()))
                .contains(new ShardId("IN", 27));
        assertThat(new ShardId("IN", 27).schemaName()).isEqualTo("in_s27");
    }

    @Test
    void unknownCountriesAndInvalidIdsRouteNowhere() {
        long unknownCountry = IdCodec.encode(Instant.parse("2026-11-15T00:00:00Z"), 5, 1, 1);
        assertThat(router.forId(unknownCountry)).isEmpty();
        assertThat(router.forId(-1)).isEmpty();
        assertThatThrownBy(() -> router.forGrower("XX", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registryRejectsDuplicateIndexes() {
        var clash = new Country("US", 46, 11, "NA", ZoneId.of("America/Chicago"));
        assertThatThrownBy(() -> new CountryRegistry(List.of(IN, clash))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void schemaNamesAreAlwaysSafeIdentifiers() {
        assertThat(ShardId.isValidSchemaName("in_s07")).isTrue();
        assertThat(ShardId.isValidSchemaName("in_s07; drop")).isFalse();
        assertThatThrownBy(() -> new ShardId("in", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShardId("IN", 64)).isInstanceOf(IllegalArgumentException.class);
        assertThat(List.of(IN, BR).stream().collect(Collectors.toMap(Country::code, Function.identity()))).hasSize(2);
    }
}
