package com.growercare.shard;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Immutable lookup of active countries by code, 6-bit index and public {@code case_no} slot.
 * Built once at startup (from {@code ref.country}) and safe to share across threads.
 */
public final class CountryRegistry {

    private final Map<String, Country> byCode;
    private final Map<Integer, Country> byIndex;
    private final Map<Integer, Country> byCaseNoCc;

    public CountryRegistry(Collection<Country> countries) {
        // Collectors.toUnmodifiableMap throws on duplicate keys, which is exactly the integrity check we want.
        this.byCode = countries.stream().collect(Collectors.toUnmodifiableMap(Country::code, Function.identity()));
        this.byIndex = countries.stream().collect(Collectors.toUnmodifiableMap(Country::countryIx, Function.identity()));
        this.byCaseNoCc = countries.stream().collect(Collectors.toUnmodifiableMap(Country::caseNoCc, Function.identity()));
    }

    public Optional<Country> byCode(String code) {
        return Optional.ofNullable(code).map(byCode::get);
    }

    public Optional<Country> byIndex(int countryIx) {
        return Optional.ofNullable(byIndex.get(countryIx));
    }

    public Optional<Country> byCaseNoCc(int caseNoCc) {
        return Optional.ofNullable(byCaseNoCc.get(caseNoCc));
    }

    public Collection<Country> all() {
        return byCode.values();
    }
}
