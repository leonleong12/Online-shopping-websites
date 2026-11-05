package com.onlineshop;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Additive migration; keep existing AI settings and the previously selected Responses behavior. */
@Component
public class SupportProtocolMigration implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SupportProtocolMigration.class);
    private final JdbcTemplate db;

    public SupportProtocolMigration(JdbcTemplate db) { this.db = db; }

    @Override
    public void run(ApplicationArguments args) {
        if (db.queryForObject("select COL_LENGTH(N'dbo.support_config', N'ai_protocol')", Integer.class) != null) return;
        Integer canAlter = db.queryForObject("select HAS_PERMS_BY_NAME(N'dbo.support_config', N'OBJECT', N'ALTER')", Integer.class);
        if (!Integer.valueOf(1).equals(canAlter)) {
            log.warn("AI protocol migration requires a database administrator to run migration_support_protocol.sql; existing storefront remains available.");
            return;
        }
        db.execute("""
                IF OBJECT_ID(N'dbo.support_config', N'U') IS NOT NULL
                   AND COL_LENGTH(N'dbo.support_config', N'ai_protocol') IS NULL
                ALTER TABLE dbo.support_config ADD ai_protocol NVARCHAR(32) NOT NULL
                    CONSTRAINT df_support_config_protocol DEFAULT N'RESPONSES'
                    CONSTRAINT chk_support_config_protocol CHECK (ai_protocol IN ('CHAT_COMPLETIONS','RESPONSES','ANTHROPIC_MESSAGES'));
                """);
    }
}
