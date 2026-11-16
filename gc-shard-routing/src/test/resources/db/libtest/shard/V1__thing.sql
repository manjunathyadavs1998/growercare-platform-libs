-- Every placeholder the migrator provides must resolve.
CREATE TABLE ${shard_schema}.thing (
    id        bigint PRIMARY KEY,
    country   char(2) NOT NULL DEFAULT '${country_code}',
    ix        int     NOT NULL DEFAULT ${country_ix},
    shard_ix  int     NOT NULL DEFAULT ${shard_ix},
    shard_ix2 text    NOT NULL DEFAULT '${shard_ix2}',
    cc        int     NOT NULL DEFAULT ${case_no_cc},
    tz        text    NOT NULL DEFAULT '${time_zone}',
    app_role  text    NOT NULL DEFAULT '${app_role}'
);
