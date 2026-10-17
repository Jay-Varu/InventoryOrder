package org.example;

import org.example.model.Order;
import org.example.model.OrderLine;
import org.example.model.Product;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Business logic layer — all inventory rules live here.
 * This class has NO knowledge of how commands were typed; it only works with
 * Java types and throws exceptions for error conditions.
 *
 * Main.java calls these methods and formats the output.
 * The simulate() method calls placeOrder() — the same method used by the CLI.
 */
public class InventoryService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // -------------------------------------------------------------------------
    // ADD PRODUCT
    // -------------------------------------------------------------------------

    /**
     * Adds a new product, or increases its stock if the SKU already exists.
     *
     * @param sku    product identifier (case-sensitive)
     * @param name   human-readable product name
     * @param price  price in whole paise (must be > 0)
     * @param stock  units to add (must be > 0)
     * @throws IllegalArgumentException for invalid inputs
     * @throws SQLException             for database errors
     */
    public void addProduct(String sku, String name, long price, int stock)
            throws IllegalArgumentException, SQLException {

        if (sku == null || sku.isBlank())  throw new IllegalArgumentException("SKU cannot be empty");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name cannot be empty");
        if (price <= 0)  throw new IllegalArgumentException("Price must be greater than 0 paise");
        if (stock <= 0)  throw new IllegalArgumentException("Stock quantity must be greater than 0");

        try (Connection conn = Database.getConnection()) {
            // Check if SKU already exists
            boolean exists = false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM products WHERE sku = ?")) {
                ps.setString(1, sku);
                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                // Increase stock for the existing product
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE products SET stock = stock + ? WHERE sku = ?")) {
                    ps.setInt(1, stock);
                    ps.setString(2, sku);
                    ps.executeUpdate();
                }
                System.out.printf("Updated: SKU '%s' stock increased by %d.%n", sku, stock);
            } else {
                // Insert new product
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO products (sku, name, price, stock) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, sku);
                    ps.setString(2, name);
                    ps.setLong(3, price);
                    ps.setInt(4, stock);
                    ps.executeUpdate();
                }
                System.out.printf("Added: SKU '%s' (%s) at %d paise, stock=%d.%n",
                        sku, name, price, stock);
            }
        }
    }

    // -------------------------------------------------------------------------
    // PLACE ORDER
    // -------------------------------------------------------------------------

    /**
     * Represents one line in an order request (input-only, before DB insert).
     */
    public record OrderRequest(String sku, int quantity) {}

    /**
     * Places an order atomically. Either ALL lines are fulfilled, or NONE are.
     * Uses BEGIN IMMEDIATE to acquire a write lock before reading stock values,
     * preventing the race condition where two threads both see sufficient stock.
     *
     * @param customer the customer name
     * @param lines    list of (sku, quantity) pairs — 1 to 5 items
     * @return the created Order
     * @throws IllegalArgumentException if input is invalid or stock is insufficient
     * @throws SQLException             for database errors
     */
    public Order placeOrder(String customer, List<OrderRequest> lines)
            throws IllegalArgumentException, SQLException {

        if (customer == null || customer.isBlank())
            throw new IllegalArgumentException("Customer name cannot be empty");
        if (lines == null || lines.isEmpty())
            throw new IllegalArgumentException("Order must have at least 1 line");
        if (lines.size() > 5)
            throw new IllegalArgumentException("Order cannot have more than 5 lines");

        for (OrderRequest line : lines) {
            if (line.quantity() <= 0)
                throw new IllegalArgumentException(
                        "Quantity for SKU '" + line.sku() + "' must be greater than 0");
        }

        try (Connection conn = Database.getConnection()) {
            // Disable auto-commit so we can control the transaction manually
            conn.setAutoCommit(false);

            try {
                // BEGIN IMMEDIATE acquires a write lock NOW, before any reads.
                // This prevents two threads from both reading sufficient stock
                // and both proceeding to decrement it.
                try (Statement st = conn.createStatement()) {
                    st.execute("BEGIN IMMEDIATE");
                }

                long total = 0;
                List<Long> prices = new ArrayList<>();

                // Step 1: Validate stock for every line inside the locked transaction
                for (OrderRequest line : lines) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT price, stock FROM products WHERE sku = ?")) {
                        ps.setString(1, line.sku());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalArgumentException(
                                        "SKU '" + line.sku() + "' does not exist");
                            }
                            long currentPrice = rs.getLong("price");
                            int currentStock  = rs.getInt("stock");
                            if (currentStock < line.quantity()) {
                                throw new IllegalArgumentException(
                                        "SKU '" + line.sku() + "' is short (has "
                                        + currentStock + ", need " + line.quantity() + ")");
                            }
                            prices.add(currentPrice);
                            total += (long) line.quantity() * currentPrice;
                        }
                    }
                }

                // Step 2: All lines passed — insert the order
                String createdAt = LocalDateTime.now().format(TIMESTAMP_FORMAT);
                int orderId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO orders (customer, created_at, status, total) VALUES (?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, customer);
                    ps.setString(2, createdAt);
                    ps.setString(3, "PLACED");
                    ps.setLong(4, total);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Failed to retrieve order ID");
                        orderId = keys.getInt(1);
                    }
                }

                // Step 3: Insert order lines and decrement stock
                for (int i = 0; i < lines.size(); i++) {
                    OrderRequest line = lines.get(i);
                    long priceAtOrder = prices.get(i);

                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO order_lines (order_id, sku, quantity, price_at_order) VALUES (?,?,?,?)")) {
                        ps.setInt(1, orderId);
                        ps.setString(2, line.sku());
                        ps.setInt(3, line.quantity());
                        ps.setLong(4, priceAtOrder);
                        ps.executeUpdate();
                    }

                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE products SET stock = stock - ? WHERE sku = ?")) {
                        ps.setInt(1, line.quantity());
                        ps.setString(2, line.sku());
                        ps.executeUpdate();
                    }
                }

                conn.commit();
                return new Order(orderId, customer, createdAt, "PLACED", total);

            } catch (Exception e) {
                // Roll back every change if anything went wrong
                try { conn.rollback(); } catch (SQLException ignored) {}
                if (e instanceof IllegalArgumentException iae) throw iae;
                throw new SQLException("Order failed: " + e.getMessage(), e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // CANCEL ORDER
    // -------------------------------------------------------------------------

    /**
     * Cancels a PLACED order and restores its stock.
     * Throws an error if the order is already cancelled (prevents double restore).
     *
     * @param orderId the ID of the order to cancel
     * @throws IllegalArgumentException if the order is not found or already cancelled
     * @throws SQLException             for database errors
     */
    public void cancelOrder(int orderId) throws IllegalArgumentException, SQLException {
        if (orderId <= 0)
            throw new IllegalArgumentException("Order ID must be a positive integer");

        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);

            try {
                try (Statement st = conn.createStatement()) {
                    st.execute("BEGIN IMMEDIATE");
                }

                // Check order exists and its current status
                String currentStatus;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status FROM orders WHERE id = ?")) {
                    ps.setInt(1, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException(
                                    "Order " + orderId + " does not exist");
                        }
                        currentStatus = rs.getString("status");
                    }
                }

                if ("CANCELLED".equals(currentStatus)) {
                    throw new IllegalArgumentException(
                            "Order " + orderId + " is already cancelled");
                }

                // Mark order as cancelled
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE orders SET status = 'CANCELLED' WHERE id = ?")) {
                    ps.setInt(1, orderId);
                    ps.executeUpdate();
                }

                // Restore stock for every line in the order
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT sku, quantity FROM order_lines WHERE order_id = ?")) {
                    ps.setInt(1, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String sku      = rs.getString("sku");
                            int    quantity = rs.getInt("quantity");
                            try (PreparedStatement upd = conn.prepareStatement(
                                    "UPDATE products SET stock = stock + ? WHERE sku = ?")) {
                                upd.setInt(1, quantity);
                                upd.setString(2, sku);
                                upd.executeUpdate();
                            }
                        }
                    }
                }

                conn.commit();
                System.out.printf("Order %d cancelled and stock restored.%n", orderId);

            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                if (e instanceof IllegalArgumentException iae) throw iae;
                throw new SQLException("Cancel failed: " + e.getMessage(), e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // LOW STOCK
    // -------------------------------------------------------------------------

    /**
     * Returns all products with stock strictly below the given threshold,
     * sorted by stock ascending (lowest stock first).
     *
     * @param below stock threshold (exclusive). Default is 5.
     * @return list of products with low stock
     * @throws SQLException for database errors
     */
    public List<Product> lowStock(int below) throws SQLException {
        if (below <= 0)
            throw new IllegalArgumentException("--below value must be greater than 0");

        List<Product> result = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT sku, name, price, stock FROM products WHERE stock < ? ORDER BY stock ASC")) {
            ps.setInt(1, below);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Product(
                            rs.getString("sku"),
                            rs.getString("name"),
                            rs.getLong("price"),
                            rs.getInt("stock")));
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // SIMULATE
    // -------------------------------------------------------------------------

    /**
     * Concurrency stress test:
     *  1. Inserts/resets product P1 with stock = 10.
     *  2. Starts 20 threads simultaneously (via CountDownLatch).
     *  3. Each thread calls placeOrder() for 1 unit of P1 — exactly like a real order.
     *  4. Prints how many succeeded, how many failed, and verifies stock = 0.
     *
     * The correctness guarantee comes from BEGIN IMMEDIATE in placeOrder():
     * only one thread can hold the write lock at a time, so stock cannot go below 0.
     *
     * @throws SQLException for database setup errors
     */
    public void simulate() throws SQLException {
        final String SIMULATE_SKU = "P1";
        final int    INITIAL_STOCK = 10;
        final int    THREAD_COUNT  = 20;

        // --- Setup: insert P1 fresh (or reset if it already exists) ---
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            }
            try {
                // Remove existing product so we start clean
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM order_lines WHERE sku = ?")) {
                    ps.setString(1, SIMULATE_SKU);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM orders WHERE id IN " +
                        "(SELECT DISTINCT order_id FROM order_lines WHERE sku = ?)")) {
                    ps.setString(1, SIMULATE_SKU);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM products WHERE sku = ?")) {
                    ps.setString(1, SIMULATE_SKU);
                    ps.executeUpdate();
                }
                // Insert fresh P1 at 100 paise per unit, stock = 10
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO products (sku, name, price, stock) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, SIMULATE_SKU);
                    ps.setString(2, "Simulate Product");
                    ps.setLong(3, 100L);
                    ps.setInt(4, INITIAL_STOCK);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw new SQLException("Simulate setup failed: " + e.getMessage(), e);
            }
        }

        System.out.printf("%nSimulate: added %s with stock=%d. Starting %d threads...%n",
                SIMULATE_SKU, INITIAL_STOCK, THREAD_COUNT);

        // --- Concurrency: all 20 threads start at the same instant ---
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        // A latch set to THREAD_COUNT: each thread calls countDown() when ready,
        // then all block on await() until the count reaches 0 — simultaneous start.
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            final String customerName = "customer_" + (i + 1);
            Thread t = new Thread(() -> {
                try {
                    startGate.await(); // Wait for the signal to start
                    List<OrderRequest> lines = List.of(new OrderRequest(SIMULATE_SKU, 1));
                    placeOrder(customerName, lines);   // Same method as the CLI uses
                    successCount.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    failCount.incrementAndGet(); // Out-of-stock or invalid
                } catch (Exception e) {
                    failCount.incrementAndGet(); // DB busy or other error
                } finally {
                    doneLatch.countDown();
                }
            });
            threads.add(t);
            t.start();
        }

        // Release all threads at once
        startGate.countDown();

        // Wait for all threads to finish
        try {
            doneLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Simulate interrupted");
        }

        // --- Verify final state ---
        int finalStock = -1;
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT stock FROM products WHERE sku = ?")) {
            ps.setString(1, SIMULATE_SKU);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) finalStock = rs.getInt("stock");
            }
        }

        System.out.printf("%nSimulate results:%n");
        System.out.printf("  Succeeded : %d%n", successCount.get());
        System.out.printf("  Failed    : %d%n", failCount.get());
        System.out.printf("  P1 stock  : %d%n", finalStock);
        System.out.printf("  Rule holds: %s%n",
                (successCount.get() == 10 && failCount.get() == 10 && finalStock == 0)
                        ? "YES ✓" : "NO ✗ — check transaction isolation!");
    }
}
