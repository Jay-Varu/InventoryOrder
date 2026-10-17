package org.example;

import org.example.model.Product;

import java.sql.*;

public class DatabaseTestHelper {

    public static void initTestDb() throws SQLException {
        Database.initSchema();
    }

    public static Product findProduct(String sku) throws SQLException {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT sku, name, price, stock FROM products WHERE sku = ?")) {
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Product(
                            rs.getString("sku"),
                            rs.getString("name"),
                            rs.getLong("price"),
                            rs.getInt("stock"));
                }
            }
        }
        return null;
    }

    public static String getOrderStatus(int orderId) throws SQLException {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM orders WHERE id = ?")) {
            ps.setInt(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("status");
            }
        }
        return null;
    }

    public static long getOrderLinePriceAtOrder(int orderId, String sku) throws SQLException {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT price_at_order FROM order_lines WHERE order_id = ? AND sku = ?")) {
            ps.setInt(1, orderId);
            ps.setString(2, sku);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("price_at_order");
            }
        }
        return -1;
    }

    public static int countOrdersWithSku(String sku, String status) throws SQLException {
        try (Connection conn = Database.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(DISTINCT o.id)
                FROM orders o
                JOIN order_lines ol ON o.id = ol.order_id
                WHERE ol.sku = ? AND o.status = ?
                """)) {
            ps.setString(1, sku);
            ps.setString(2, status);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }
}
