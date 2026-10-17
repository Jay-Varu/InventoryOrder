package org.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Database layer — responsible only for:
 *  1. Providing JDBC connections to SQLite.
 *  2. Creating the schema on first run (CREATE TABLE IF NOT EXISTS).
 *
 * Every connection has:
 *  - foreign_keys = ON  (enforces referential integrity)
 *  - busy_timeout = 5000 ms (threads wait up to 5 s instead of failing instantly)
 *  - journal_mode = WAL  (allows concurrent reads while a write is in progress)
 */
public class Database {

    // Path to the SQLite file.
    // Tests set the system property "test.db" to use a separate file, e.g. "test_inventory.db".
    // Production always uses "database.db" in the working directory.
    private static String getDbUrl() {
        String testDb = System.getProperty("test.db");
        if (testDb != null && !testDb.isBlank()) {
            return "jdbc:sqlite:" + testDb;
        }
        return "jdbc:sqlite:database.db";
    }

    /**
     * Opens and returns a new JDBC connection.
     * Each thread must call this separately — SQLite connections are NOT thread-safe.
     */
    public static Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(getDbUrl());
        // Apply per-connection settings
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA journal_mode = WAL");
        }
        return conn;
    }

    /**
     * Creates all tables if they do not already exist.
     * Safe to call every time the program starts.
     */
    public static void initSchema() throws SQLException {
        try (Connection conn = getConnection();
             Statement st = conn.createStatement()) {

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS products (
                    sku     TEXT    PRIMARY KEY,
                    name    TEXT    NOT NULL,
                    price   INTEGER NOT NULL CHECK(price > 0),
                    stock   INTEGER NOT NULL CHECK(stock >= 0)
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS orders (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    customer    TEXT    NOT NULL,
                    created_at  TEXT    NOT NULL,
                    status      TEXT    NOT NULL DEFAULT 'PLACED'
                                        CHECK(status IN ('PLACED','CANCELLED')),
                    total       INTEGER NOT NULL
                )
                """);

            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS order_lines (
                    order_id        INTEGER NOT NULL REFERENCES orders(id),
                    sku             TEXT    NOT NULL REFERENCES products(sku),
                    quantity        INTEGER NOT NULL CHECK(quantity > 0),
                    price_at_order  INTEGER NOT NULL,
                    PRIMARY KEY (order_id, sku)
                )
                """);
        }
    }
}
