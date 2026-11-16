-- Minimal ref schema the migrator and CountryRegistryLoader read (mirrors case-service common V1/V2).
CREATE TABLE ref.country (
    country_code char(2)     PRIMARY KEY,
    country_ix   smallint    NOT NULL,
    case_no_cc   smallint    NOT NULL,
    region_code  varchar(8)  NOT NULL,
    time_zone    varchar(64) NOT NULL,
    active       boolean     NOT NULL
);
CREATE TABLE ref.shard (
    country_code char(2)     NOT NULL REFERENCES ref.country,
    shard_no     smallint    NOT NULL,
    schema_name  varchar(16) NOT NULL,
    PRIMARY KEY (country_code, shard_no)
);
INSERT INTO ref.country VALUES ('IN', 46, 71, 'APAC', 'Asia/Kolkata', true),
                               ('BR', 8, 31, 'LATAM', 'America/Sao_Paulo', true),
                               ('KE', 30, 54, 'EMEA', 'Africa/Nairobi', false);
INSERT INTO ref.shard VALUES ('IN', 0, 'in_s00'), ('IN', 1, 'in_s01'), ('BR', 0, 'br_s00'), ('BR', 1, 'br_s01');
