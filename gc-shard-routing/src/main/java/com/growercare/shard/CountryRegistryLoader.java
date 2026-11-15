package com.growercare.shard;

import java.time.ZoneId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Builds the {@link CountryRegistry} from {@code ref.country} (active rows only). */
public final class CountryRegistryLoader {

    private static final String SQL = """
            SELECT country_code, country_ix, case_no_cc, region_code, time_zone
              FROM ref.country
             WHERE active
            """;

    private CountryRegistryLoader() { }

    public static CountryRegistry load(JdbcClient jdbc) {
        var countries = jdbc.sql(SQL)
                .query((rs, n) -> new Country(
                        rs.getString("country_code"),
                        rs.getInt("country_ix"),
                        rs.getInt("case_no_cc"),
                        rs.getString("region_code"),
                        ZoneId.of(rs.getString("time_zone"))))
                .list();
        if (countries.isEmpty()) {
            throw new IllegalStateException("No active countries in ref.country");
        }
        return new CountryRegistry(countries);
    }
}
