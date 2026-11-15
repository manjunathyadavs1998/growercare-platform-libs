package com.growercare.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IdCodecTest {

    private static final Instant T = Instant.parse("2026-11-15T10:15:30.123Z");

    @Test
    void encodeDecodeRoundTripsAllFields() {
        IntStream.of(0, 1, 27, 63).forEach(shard -> {
            long id = IdCodec.encode(T, 46, shard, 1023);
            var decoded = IdCodec.decode(id);
            assertThat(decoded.createdAt()).isEqualTo(T);
            assertThat(decoded.countryIx()).isEqualTo(46);
            assertThat(decoded.shardNo()).isEqualTo(shard);
            assertThat(decoded.sequence()).isEqualTo(1023);
        });
    }

    @Test
    void idsAreTimeOrderedAcrossCountriesAndShards() {
        long earlier = IdCodec.encode(T, 63, 63, 1023);
        long later = IdCodec.encode(T.plusMillis(1), 0, 0, 0);
        assertThat(later).isGreaterThan(earlier);
    }

    @Test
    void floorIsTheSmallestIdOfThatMillisecond() {
        long floor = IdCodec.floor(T);
        assertThat(IdCodec.encode(T, 0, 0, 0)).isEqualTo(floor);
        assertThat(IdCodec.encode(T.minusMillis(1), 63, 63, 1023)).isLessThan(floor);
    }

    @Test
    void rejectsOutOfRangeComponentsAndPreEpochInstants() {
        assertThatThrownBy(() -> IdCodec.encode(T, 64, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdCodec.encode(T, 0, 0, 1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdCodec.floor(Instant.parse("2025-12-31T23:59:59Z"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdCodec.decode(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
