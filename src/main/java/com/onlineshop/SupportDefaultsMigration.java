package com.onlineshop;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Repair legacy reset seeds without replacing a shop's configured AI service or custom greeting. */
@Component
@Order(100)
public class SupportDefaultsMigration implements ApplicationRunner {
    static final String WELCOME = "您好，欢迎来到 NovaMart。今天想为日常添置点什么好物？";
    private final JdbcTemplate db;

    public SupportDefaultsMigration(JdbcTemplate db) { this.db = db; }

    @Override
    public void run(ApplicationArguments args) {
        if (db.queryForObject("select COL_LENGTH(N'dbo.support_config', N'ai_protocol')", Integer.class) == null) return;
        db.update("""
                update dbo.support_config set ai_base_url=N'',ai_model=N'',ai_protocol=N'CHAT_COMPLETIONS'
                where id=1 and nullif(ltrim(rtrim(ai_api_key)),N'') is null
                  and ai_base_url=N'https://api.aijws.com/v1' and ai_model=N'qwen3.8-flash'
                  and ai_protocol=N'RESPONSES'
                """);
        // sqlcmd's legacy Windows code page can turn a UTF-8 seed into mojibake.
        // Match only the known greeting's corrupted opening plus the NovaMart brand.
        db.update("""
                update dbo.support_config set welcome_message=?
                where id=1 and welcome_message like N'%NovaMart%'
                  and (welcome_message like ? or welcome_message like ? or welcome_message like ?)
                """, WELCOME, "鎮%ソ%NovaMart%", "鎮%濂%NovaMart%", "æ‚¨%NovaMart%");
    }
}
