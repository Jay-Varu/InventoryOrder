package org.example;

import org.example.model.Order;
import org.example.model.Product;
import org.junit.jupiter.api.*;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated tests for all business operations.
 *
 * IMPORTANT: Tests use an isolated in-memory SQLite database ("jdbc:sqlite::memory:").
 * This means each test run starts with a clean slate and never touches production data.
 *
 * We test database STATE directly (via SQL queries), not just printed output.
 * This matches what the assessment requires: "Verify database state, not merely printed output."
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InventoryServiceTest {

    private static InventoryService service;

    /**
     * Before all tests: switch to an in-memory database and initialize the schema.
     * In-memory SQLite (:memory:) is isolated per connection, so we use a file-based
     * temp DB to allow multiple connections within tests.
     */
    @BeforeAll
    static void setupDatabase() throws SQLException {
        // Use a temp file DB for tests so multiple connections can share state
        // (SQLite :memory: is not shared across connections)
        System.setProperty("test.db", "test_inventory.db");
        DatabaseTestHelper.initTestDb();
        service = new InventoryService();
    }

    /**
     * After all tests: clean up the temp database file.
     */
    @AfterAll
    static void cleanup() {
        new File("test_inventory.db").delete();
    }

    // =========================================================================
    // TEST 1: Add Product — new product
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Add a new product and verify it exists in the database")
    void testAddNewProduct() throws SQLException {
        service.addProduct("SKU001", "Test Widget", 5000L, 100);

        // Verify directly in DB
        Product p = DatabaseTestHelper.findProduct("SKU001");
        assertNotNull(p, "Product SKU001 should exist in the database");
        assertEquals("Test Widget", p.getName());
        assertEquals(5000L, p.getPrice(), "Price should be 5000 paise");
        assertEquals(100, p.getStock(), "Stock should be 100");
    }

    // =========================================================================
    // TEST 2: Add Product — increases stock for existing SKU
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Adding an existing SKU increases its stock")
    void testAddExistingSkuIncreasesStock() throws SQLException {
        // Add fresh product
        service.addProduct("SKU002", "Stackable Item", 1000L, 50);
        // Add again with same SKU
        service.addProduct("SKU002", "Stackable Item", 1000L, 30);

        Product p = DatabaseTestHelper.findProduct("SKU002");
        assertNotNull(p);
        assertEquals(80, p.getStock(), "Stock should be 50 + 30 = 80 after two adds");
    }

    // =========================================================================
    // TEST 3: Place order — success, stock decremented, order and lines created
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Successful order decrements stock and creates order + order lines")
    void testPlaceOrderSuccess() throws SQLException {
        service.addProduct("SKU003", "Order Product", 2000L, 10);

        Order order = service.placeOrder("Alice", List.of(
                new InventoryService.OrderRequest("SKU003", 3)));

        // Order should be returned with correct fields
        assertTrue(order.getId() > 0, "Order ID should be positive");
        assertEquals("Alice", order.getCustomer());
        assertEquals("PLACED", order.getStatus());
        assertEquals(6000L, order.getTotal(), "Total = 3 * 2000 = 6000 paise");

        // Stock should be reduced
        Product p = DatabaseTestHelper.findProduct("SKU003");
        assertNotNull(p);
        assertEquals(7, p.getStock(), "Stock should be 10 - 3 = 7");

        // Order line should exist with frozen price
        long frozenPrice = DatabaseTestHelper.getOrderLinePriceAtOrder(order.getId(), "SKU003");
        assertEquals(2000L, frozenPrice, "price_at_order should be 2000 paise");
    }

    // =========================================================================
    // TEST 4: Place order — insufficient stock, all-or-nothing rollback
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Order with insufficient stock is rejected; no stock changes made")
    void testPlaceOrderInsufficientStock() throws SQLException {
        service.addProduct("SKU004A", "Product A", 1000L, 5);
        service.addProduct("SKU004B", "Product B", 1000L, 1); // only 1 in stock

        // Try to order 2 of A (ok) and 3 of B (not ok) — should fail entirely
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.placeOrder("Bob", List.of(
                        new InventoryService.OrderRequest("SKU004A", 2),
                        new InventoryService.OrderRequest("SKU004B", 3)
                ))
        );
        assertTrue(ex.getMessage().contains("SKU004B"), "Error should mention the short SKU");

        // Stock of BOTH products must be unchanged (atomic rollback)
        Product a = DatabaseTestHelper.findProduct("SKU004A");
        Product b = DatabaseTestHelper.findProduct("SKU004B");
        assertEquals(5, a.getStock(), "SKU004A stock should be unchanged");
        assertEquals(1, b.getStock(), "SKU004B stock should be unchanged");
    }

    // =========================================================================
    // TEST 5: Cancel order — stock is restored
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("Cancelling an order restores its stock")
    void testCancelOrderRestoresStock() throws SQLException {
        service.addProduct("SKU005", "Cancel Product", 3000L, 20);
        Order order = service.placeOrder("Charlie", List.of(
                new InventoryService.OrderRequest("SKU005", 5)));

        // Verify stock was decremented
        assertEquals(15, DatabaseTestHelper.findProduct("SKU005").getStock());

        service.cancelOrder(order.getId());

        // Stock should be restored
        assertEquals(20, DatabaseTestHelper.findProduct("SKU005").getStock(),
                "Stock should be fully restored after cancel");

        // Status should be CANCELLED in DB
        String status = DatabaseTestHelper.getOrderStatus(order.getId());
        assertEquals("CANCELLED", status);
    }

    // =========================================================================
    // TEST 6: Cancel order — prevents double cancellation
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("Cancelling an already-cancelled order returns an error")
    void testDoubleCancelPrevented() throws SQLException {
        service.addProduct("SKU006", "Double Cancel", 500L, 10);
        Order order = service.placeOrder("Dave", List.of(
                new InventoryService.OrderRequest("SKU006", 2)));

        service.cancelOrder(order.getId()); // first cancel — OK
        int stockAfterFirstCancel = DatabaseTestHelper.findProduct("SKU006").getStock();

        // Second cancel should throw
        assertThrows(IllegalArgumentException.class, () ->
                service.cancelOrder(order.getId()));

        // Stock should NOT have increased a second time
        int stockAfterSecondAttempt = DatabaseTestHelper.findProduct("SKU006").getStock();
        assertEquals(stockAfterFirstCancel, stockAfterSecondAttempt,
                "Stock must not be restored twice");
    }

    // =========================================================================
    // TEST 7: Low-stock listing — correct filtering and order
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("low-stock returns products below threshold, sorted ascending by stock")
    void testLowStock() throws SQLException {
        // Add products with known stock levels
        service.addProduct("LOW001", "Very Low", 100L, 1);
        service.addProduct("LOW002", "Low",      100L, 3);
        service.addProduct("LOW003", "Normal",   100L, 10);

        List<Product> below5 = service.lowStock(5);

        // Only LOW001 and LOW002 should appear (stock < 5)
        List<String> skus = below5.stream().map(Product::getSku).toList();
        assertTrue(skus.contains("LOW001"), "LOW001 (stock=1) should be in low-stock list");
        assertTrue(skus.contains("LOW002"), "LOW002 (stock=3) should be in low-stock list");
        assertFalse(skus.contains("LOW003"), "LOW003 (stock=10) must NOT be in list");

        // Verify sorted by stock ascending
        for (int i = 0; i < below5.size() - 1; i++) {
            assertTrue(below5.get(i).getStock() <= below5.get(i + 1).getStock(),
                    "Products should be sorted by stock ascending");
        }
    }

    // =========================================================================
    // TEST 8: Historical prices — unchanged when product price changes
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("price_at_order is frozen at order time; unaffected by later price changes")
    void testHistoricalPriceFrozen() throws SQLException {
        service.addProduct("HIST001", "History Product", 1000L, 20);

        Order order = service.placeOrder("Eve", List.of(
                new InventoryService.OrderRequest("HIST001", 2)));

        // Now simulate a price change (update the product directly in DB)
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE products SET price = 9999 WHERE sku = 'HIST001'")) {
            ps.executeUpdate();
        }

        // The order line price should still be the original price
        long frozenPrice = DatabaseTestHelper.getOrderLinePriceAtOrder(order.getId(), "HIST001");
        assertEquals(1000L, frozenPrice,
                "price_at_order must remain 1000 even after the product price changed to 9999");
    }

    // =========================================================================
    // TEST 9: Simulate — 20 threads, 10 succeed, 10 fail, stock = 0
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(9)
    @DisplayName("simulate: exactly 10 of 20 concurrent orders succeed; stock ends at 0")
    void testSimulate() throws Exception {
        service.simulate();

        // Verify final stock in DB
        Product p1 = DatabaseTestHelper.findProduct("P1");
        assertNotNull(p1, "P1 should exist after simulate");
        assertEquals(0, p1.getStock(), "P1 stock must be 0 after simulate (all 10 units sold)");

        // Count PLACED orders for P1
        int placedCount  = DatabaseTestHelper.countOrdersWithSku("P1", "PLACED");
        assertEquals(10, placedCount, "Exactly 10 orders for P1 should be PLACED");
    }

    // =========================================================================
    // TEST 10: Validation — reject invalid inputs
    // =========================================================================

    @Test
    @org.junit.jupiter.api.Order(10)
    @DisplayName("Validation rejects invalid input gracefully")
    void testValidation() {
        // Negative price
        assertThrows(IllegalArgumentException.class, () ->
                service.addProduct("V001", "Bad Price", -100L, 5));

        // Zero quantity in order
        assertThrows(IllegalArgumentException.class, () ->
                service.placeOrder("Frank", List.of(
                        new InventoryService.OrderRequest("V001", 0))));

        // Non-existent order cancel
        assertThrows(IllegalArgumentException.class, () ->
                service.cancelOrder(999999));

        // Low-stock with invalid threshold
        assertThrows(IllegalArgumentException.class, () ->
                service.lowStock(-1));
    }
}
