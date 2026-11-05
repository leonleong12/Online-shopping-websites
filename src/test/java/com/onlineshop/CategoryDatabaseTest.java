package com.onlineshop;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQL Server checks; all category/product fixtures are rolled back. */
@EnabledIfEnvironmentVariable(named="NOVAMART_CATEGORY_IT", matches="1")
class CategoryDatabaseTest {
    static HikariDataSource source;
    static JdbcTemplate db;
    static TransactionTemplate transaction;
    static CategoryController categories;
    static ApiController products;
    static String prefix;

    @BeforeAll static void setup() {
        String host = System.getenv().getOrDefault("DB_HOST", "localhost\\SQLEXPRESS01");
        if (!host.startsWith("localhost\\")) throw new IllegalStateException("Tests require a local SQL instance");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlserver://" + host + ";databaseName=online_shop;encrypt=false;trustServerCertificate=true");
        config.setUsername(System.getenv().getOrDefault("DB_USER", "online_shop_app"));
        config.setPassword(Objects.requireNonNull(System.getenv("DB_PASSWORD")));
        source = new HikariDataSource(config);
        db = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        SessionAuth auth = mock(SessionAuth.class);
        categories = new CategoryController(db, auth);
        products = new ApiController(db, mock(OrderExpirationService.class), mock(OrderFulfillmentService.class), auth, mock(WalletService.class));
        prefix = "category_qa_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @AfterAll static void close() { if (source != null) source.close(); }

    void rollback(Runnable check) {
        transaction.executeWithoutResult(status -> { try { check.run(); } finally { status.setRollbackOnly(); } });
    }
    long category(String suffix, boolean visible) {
        return ((Number) categories.create("test", new CategoryController.CategoryInput(prefix + suffix, "QA", 10, visible)).get("id")).longValue();
    }
    long product(long category, String status) {
        return db.queryForObject("insert into dbo.products(category_id,name,price,stock,status) output inserted.id values(?,?,10,2,?)",
                Long.class, category, prefix, status);
    }
    void change(long id, String name, int order, boolean visible) {
        categories.update("test", id, new CategoryController.CategoryInput(name, "Updated", order, visible));
    }
    List<Long> ids() { return db.queryForList("select id from dbo.categories order by sort_order,id", Long.class); }
    Set<Long> publicIds() {
        Set<Long> result = new HashSet<>();
        categories.categories().forEach(row -> result.add(((Number)row.get("id")).longValue()));
        return result;
    }
    void error(int code, Runnable action) { assertEquals(code, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value()); }

    @Test void frontOnlyListsVisibleCategoriesWithOnSaleProducts() { rollback(() -> {
        long empty = category("empty", true), offSale = category("off", true), hidden = category("hidden", false), visible = category("visible", true);
        product(offSale, "OFF_SALE"); product(hidden, "ON_SALE"); product(visible, "ON_SALE");
        Set<Long> ids = publicIds();
        assertFalse(ids.contains(empty)); assertFalse(ids.contains(offSale)); assertFalse(ids.contains(hidden)); assertTrue(ids.contains(visible));
        var admin = categories.adminCategories("test");
        for (long id : List.of(empty, offSale, hidden, visible)) assertTrue(admin.stream().anyMatch(row -> ((Number)row.get("id")).longValue() == id));
        var off = admin.stream().filter(row -> ((Number)row.get("id")).longValue() == offSale).findFirst().orElseThrow();
        assertEquals(1, ((Number)off.get("product_count")).intValue()); assertEquals(0, ((Number)off.get("on_sale_count")).intValue());
    }); }

    @Test void renamingAndHidingPreserveProductAssignmentsAndAllProducts() { rollback(() -> {
        long id = category("rename", true), product = product(id, "ON_SALE");
        change(id, prefix + "新名称", 42, false);
        assertFalse(publicIds().contains(id));
        assertTrue(products.products(prefix, null).stream().anyMatch(row -> ((Number)row.get("id")).longValue() == product));
        assertEquals(id, db.queryForObject("select category_id from products where id=?", Long.class, product));
        assertEquals(prefix + "新名称", products.product(product).get("category_name"));
        change(id, prefix + "新名称", 42, true);
        assertTrue(publicIds().contains(id));
    }); }

    @Test void orderPersistsAndStaleOrDuplicateRequestsDoNotPartiallyUpdate() { rollback(() -> {
        category("sort1", true); category("sort2", true);
        List<Long> reversed = new ArrayList<>(ids()); Collections.reverse(reversed);
        categories.reorder("test", new CategoryController.CategoryOrder(reversed));
        assertEquals(reversed, ids());
        error(409, () -> categories.reorder("test", new CategoryController.CategoryOrder(reversed.subList(1, reversed.size()))));
        error(400, () -> categories.reorder("test", new CategoryController.CategoryOrder(List.of(reversed.getFirst(), reversed.getFirst()))));
        assertEquals(reversed, ids());
    }); }

    @Test void duplicateNameReturnsUsefulConflict() { rollback(() -> {
        category("unique", true);
        error(409, () -> category("unique", false));
    }); }

    @Test void invalidNameOrderAndMissingCategoryHaveClearErrors() { rollback(() -> {
        error(400, () -> categories.create("test", new CategoryController.CategoryInput(" ", "", 0, true)));
        error(400, () -> categories.create("test", new CategoryController.CategoryInput("A".repeat(81), "", 0, true)));
        error(400, () -> categories.create("test", new CategoryController.CategoryInput("A\nB", "", 0, true)));
        error(400, () -> categories.create("test", new CategoryController.CategoryInput("valid", "", -1, true)));
        error(404, () -> change(Long.MAX_VALUE, "valid", 0, true));
    }); }

    @Test void productEditorCanUseEmptyOrHiddenCategoryOrNoCategoryAndRejectsInvalidId() { rollback(() -> {
        long hidden = category("assign", false);
        Map<String, Object> body = new HashMap<>(Map.of("name", prefix, "price", 10, "stock", 2, "status", "ON_SALE", "categoryId", hidden));
        long id = ((Number)products.addProduct("test", body).get("id")).longValue();
        assertFalse(publicIds().contains(hidden));
        assertEquals(hidden, db.queryForObject("select category_id from products where id=?", Long.class, id));
        body.put("categoryId", null); products.editProduct("test", id, body);
        assertNull(db.queryForObject("select category_id from products where id=?", Long.class, id));
        body.put("categoryId", Long.MAX_VALUE); error(400, () -> products.editProduct("test", id, body));
        body.put("categoryId", 1.5); error(400, () -> products.editProduct("test", id, body));
    }); }
}
