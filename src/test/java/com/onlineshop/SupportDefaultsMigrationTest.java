package com.onlineshop;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportDefaultsMigrationTest {
    @Test void missingProtocolColumnDoesNotPreventStartup() {
        var db = mock(JdbcTemplate.class);
        new SupportDefaultsMigration(db).run(null);
        verify(db, never()).update(anyString());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void repairsOnlyUnconfiguredLegacySeedAndKnownCorruptedGreeting() {
        var db = mock(JdbcTemplate.class);
        when(db.queryForObject(anyString(), eq(Integer.class))).thenReturn(64);
        new SupportDefaultsMigration(db).run(null);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(db).update(sql.capture());
        assertTrue(sql.getValue().contains("ai_protocol=N'CHAT_COMPLETIONS'"));
        assertTrue(sql.getValue().contains("nullif(ltrim(rtrim(ai_api_key)),N'') is null"));
        assertTrue(sql.getValue().contains("ai_model=N'qwen3.8-flash'"));
        verify(db).update(contains("welcome_message like N'%NovaMart%'"),
            eq(SupportDefaultsMigration.WELCOME), eq("鎮%ソ%NovaMart%"), eq("鎮%濂%NovaMart%"), eq("æ‚¨%NovaMart%"));
    }
}
