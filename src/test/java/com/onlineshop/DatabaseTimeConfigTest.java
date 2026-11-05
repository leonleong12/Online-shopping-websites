package com.onlineshop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseTimeConfigTest {
    @ParameterizedTest
    @ValueSource(strings = {"Asia/Taipei", "UTC", "America/New_York"})
    void sqlUtcFieldsKeepTheirInstantRegardlessOfServerZone(String zone) throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            var builder = new Jackson2ObjectMapperBuilder();
            new DatabaseTimeConfig().databaseUtcTimestamps().customize(builder);
            var mapper = builder.build();
            // Matches a DATETIME2 column returned through JdbcTemplate.queryForList.
            var row = Map.of("order", Map.of("created_at", Timestamp.valueOf("2026-10-18 10:07:46.1209633")));
            var json = mapper.readTree(mapper.writeValueAsString(row));
            assertEquals(Instant.parse("2026-10-18T10:07:46.120963300Z"),
                    Instant.parse(json.path("order").path("created_at").asText()));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void existingUtcStringsAndNullTimestampsRemainUnchanged() throws Exception {
        var builder = new Jackson2ObjectMapperBuilder();
        new DatabaseTimeConfig().databaseUtcTimestamps().customize(builder);
        var mapper = builder.build();
        var json = mapper.readTree(mapper.writeValueAsString(Map.of("created_at", "2026-10-18T10:07:46Z",
                "order", Collections.singletonMap("shipped_at", null))));
        assertEquals("2026-10-18T10:07:46Z", json.path("created_at").asText());
        assertEquals(true, json.path("order").path("shipped_at").isNull());
    }
}
