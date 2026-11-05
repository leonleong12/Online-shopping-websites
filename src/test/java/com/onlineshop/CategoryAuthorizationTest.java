package com.onlineshop;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CategoryAuthorizationTest {
    @Test void everyManagementRouteRequiresLoginBeforeReadingOrWritingData() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CategoryController(db, new SessionAuth(db))).build();
        mvc.perform(get("/api/admin/categories")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/categories").contentType("application/json").content("{\"name\":\"test\"}")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/categories/1").contentType("application/json").content("{\"name\":\"test\"}")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/categories/order").contentType("application/json").content("{\"ids\":[1]}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(db);
    }

    @Test void buyerCannotAccessManagementRoutes() throws Exception {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList("select status,password_hash from dbo.users where id=?", 11L)).thenReturn(java.util.List.of(java.util.Map.of("status", "ACTIVE", "password_hash", "HASH")));
        when(db.queryForObject("select role from dbo.users where id=?", String.class, 11L)).thenReturn("CUSTOMER");
        SessionAuth auth = new SessionAuth(db);
        String header = "Bearer " + auth.issue(11, "HASH");
        var mvc = MockMvcBuilders.standaloneSetup(new CategoryController(db, auth)).build();
        mvc.perform(get("/api/admin/categories").header("Authorization", header)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/categories").header("Authorization", header).contentType("application/json").content("{\"name\":\"test\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/categories/1").header("Authorization", header).contentType("application/json").content("{\"name\":\"test\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/categories/order").header("Authorization", header).contentType("application/json").content("{\"ids\":[1]}")).andExpect(status().isForbidden());
        verify(db, never()).update(anyString(), any(Object[].class));
    }
}
