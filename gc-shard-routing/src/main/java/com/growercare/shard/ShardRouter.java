package com.growercare.shard;

import java.util.Optional;

/**
 * Maps business keys to the shard that owns them. Countries come only from the registry (server side),
 * never from client headers.
 * <ul>
 *   <li>New data: {@link #forGrower} — {@code fmix64(growerId) mod 64}, so a grower's cases and comments
 *       are co-located.</li>
 *   <li>Existing data: {@link #forId} / {@link #forCaseNumber} — decoded from the identifier, no lookup.</li>
 * </ul>
 * Lookups by ID return {@link Optional#empty()} for unknown countries so callers can answer 404 instead
 * of revealing anything about the ID space.
 */
public final class ShardRouter {

    private final CountryRegistry countries;

    public ShardRouter(CountryRegistry countries) {
        this.countries = countries;
    }

    public ShardId forGrower(String countryCode, long growerId) {
        var country = countries.byCode(countryCode)
                .orElseThrow(() -> new IllegalArgumentException("Country not active: " + countryCode));
        return new ShardId(country.code(), (int) Long.remainderUnsigned(fmix64(growerId), ShardId.SHARDS_PER_COUNTRY));
    }

    public Optional<ShardId> forId(long id) {
        if (id <= 0) {
            return Optional.empty();
        }
        var decoded = IdCodec.decode(id);
        return countries.byIndex(decoded.countryIx()).map(c -> new ShardId(c.code(), decoded.shardNo()));
    }

    public Optional<ShardId> forCaseNumber(CaseNumber caseNumber) {
        return countries.byCaseNoCc(caseNumber.caseNoCc()).map(c -> new ShardId(c.code(), caseNumber.shardNo()));
    }

    public Optional<Country> countryOfId(long id) {
        return id <= 0 ? Optional.empty() : countries.byIndex(IdCodec.decode(id).countryIx());
    }

    public CountryRegistry countries() {
        return countries;
    }

    /** MurmurHash3 finalizer: spreads sequential grower IDs evenly over the 64 shards. Never change it. */
    static long fmix64(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }
}
