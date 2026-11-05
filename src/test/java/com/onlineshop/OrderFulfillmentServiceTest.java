package com.onlineshop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderFulfillmentServiceTest {
    private JdbcTemplate db;
    private OrderFulfillmentService service;

    @BeforeEach
    void setUp() { db = mock(JdbcTemplate.class); service = new OrderFulfillmentService(db); }

    @Test
    void physicalShipmentSavesTrackingAndClearsUnrelatedVirtualCode() {
        when(db.queryForList(anyString(), eq(12L))).thenReturn(List.of(Map.of("status", "PROCESSING", "payment_status", "PAID")));
        when(db.update(anyString(), eq("PHYSICAL"), eq("SF123456"), isNull(), eq(12L))).thenReturn(1);
        service.ship(12, Map.of("deliveryType", "PHYSICAL", "trackingNumber", " SF123456 ", "virtualCode", "unused"));
        verify(db).update(contains("status='SHIPPED'"), eq("PHYSICAL"), eq("SF123456"), isNull(), eq(12L));
    }

    @Test
    void virtualShipmentPreservesMultilineCodeAndClearsTracking() {
        when(db.queryForList(anyString(), eq(12L))).thenReturn(List.of(Map.of("status", "PROCESSING", "payment_status", "PAID")));
        when(db.update(anyString(), eq("VIRTUAL"), isNull(), eq("卡号: ABC\n密码: XYZ"), eq(12L))).thenReturn(1);
        service.ship(12, Map.of("deliveryType", "VIRTUAL", "virtualCode", "卡号: ABC\n密码: XYZ", "trackingNumber", "unused"));
        verify(db).update(contains("shipped_at=SYSUTCDATETIME()"), eq("VIRTUAL"), isNull(), eq("卡号: ABC\n密码: XYZ"), eq(12L));
    }

    @ParameterizedTest
    @ValueSource(strings = { "PENDING_PAYMENT", "PAID", "SHIPPED", "COMPLETED", "CANCELLED" })
    void shipmentRejectsAnyOtherState(String status) {
        when(db.queryForList(anyString(), eq(12L))).thenReturn(List.of(Map.of("status", status, "payment_status", "PAID")));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.ship(12, Map.of("deliveryType", "PHYSICAL", "trackingNumber", "SF123"))).getStatusCode().value());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void unpaidOrderCannotShipEvenWhenMarkedProcessing() {
        when(db.queryForList(anyString(), eq(12L))).thenReturn(List.of(Map.of("status", "PROCESSING", "payment_status", "UNPAID")));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.ship(12, Map.of("deliveryType", "PHYSICAL", "trackingNumber", "SF123"))).getStatusCode().value());
    }

    @Test
    void invalidInputsAreRejectedBeforeDatabaseAccess() {
        for (var body : List.of(Map.of("deliveryType", "UNKNOWN"), Map.of("deliveryType", "PHYSICAL"),
                Map.of("deliveryType", "PHYSICAL", "trackingNumber", "中文 单号"),
                Map.of("deliveryType", "PHYSICAL", "trackingNumber", "1".repeat(101)),
                Map.of("deliveryType", "VIRTUAL", "virtualCode", " "),
                Map.of("deliveryType", "VIRTUAL", "virtualCode", "A".repeat(2001)))) {
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.ship(12, body)).getStatusCode().value());
        }
        verifyNoInteractions(db);
    }

    @Test
    void cannotConfirmAnotherCustomersOrder() {
        when(db.queryForList(anyString(), eq(12L), eq(99L))).thenReturn(List.of());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.confirmReceipt(12, 99)).getStatusCode().value());
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = { "PENDING_PAYMENT", "PAID", "PROCESSING", "COMPLETED", "CANCELLED" })
    void cannotConfirmBeforeShipmentOrMoreThanOnce(String status) {
        when(db.queryForList(anyString(), eq(12L), eq(7L))).thenReturn(List.of(Map.of("status", status)));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.confirmReceipt(12, 7)).getStatusCode().value());
    }

    @Test
    void ownerCanCompleteShippedOrder() {
        when(db.queryForList(anyString(), eq(12L), eq(7L))).thenReturn(List.of(Map.of("status", "SHIPPED")));
        when(db.update(anyString(), eq(12L), eq(7L))).thenReturn(1);
        service.confirmReceipt(12, 7);
        verify(db).update(contains("completed_at=SYSUTCDATETIME()"), eq(12L), eq(7L));
    }

    @Test
    void missingOrderCannotShip() {
        when(db.queryForList(anyString(), eq(12L))).thenReturn(List.of());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.ship(12, Map.of("deliveryType", "PHYSICAL", "trackingNumber", "SF123"))).getStatusCode().value());
    }
}
