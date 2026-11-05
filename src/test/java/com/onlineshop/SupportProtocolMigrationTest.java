package com.onlineshop;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupportProtocolMigrationTest {
    @Test void existingColumnDoesNotRequireDdlPrivileges() {
        var db = mock(JdbcTemplate.class);
        when(db.queryForObject(contains("COL_LENGTH"), eq(Integer.class))).thenReturn(64);
        new SupportProtocolMigration(db).run(null);
        verify(db, never()).execute(anyString());
    }

    @Test void restrictedAppAccountDoesNotCrashStorefrontOrAttemptDdl() {
        var db = mock(JdbcTemplate.class);
        when(db.queryForObject(contains("HAS_PERMS_BY_NAME"), eq(Integer.class))).thenReturn(0);
        new SupportProtocolMigration(db).run(null);
        verify(db, never()).execute(anyString());
    }

    @Test void authorizedMigrationPreservesExistingSettingsAndUsesChatDefault() {
        var db = mock(JdbcTemplate.class);
        when(db.queryForObject(contains("HAS_PERMS_BY_NAME"), eq(Integer.class))).thenReturn(1);
        new SupportProtocolMigration(db).run(null);
        verify(db).execute(contains("DEFAULT N'CHAT_COMPLETIONS'"));
        verify(db, never()).update(anyString());
    }
}
