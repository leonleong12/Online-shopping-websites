package com.onlineshop;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.ZoneOffset;

@Configuration
public class DatabaseTimeConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer databaseUtcTimestamps() {
        return builder -> builder.serializerByType(Timestamp.class, new JsonSerializer<Timestamp>() {
            @Override
            public void serialize(Timestamp value, JsonGenerator output, SerializerProvider serializers) throws IOException {
                // DATETIME2 has no zone. All database defaults/write paths store UTC,
                // while JDBC represents its wall-clock fields in the JVM's local zone.
                output.writeString(value.toLocalDateTime().toInstant(ZoneOffset.UTC).toString());
            }
        });
    }
}
