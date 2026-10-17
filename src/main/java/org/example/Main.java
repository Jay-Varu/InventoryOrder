package org.example;

import org.example.model.Order;
import org.example.model.Product;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * CLI entry point — responsible ONLY for:
 *  - Reading command-line arguments (or interactive input)
 *  - Parsing and validating format (types, counts)
 *  - Calling InventoryService methods
 *  - Formatting and printing results
 *
 * No SQL, no business rules live here.
 *
 * Usage (non-interactive, one command per run):
 *   java -jar app.jar add P1 Widget 5000 10
 *   java -jar app.jar order Alice P1:2 P2:1
 *   java -jar app.jar cancel 3
 *   java -jar app.jar low-stock
 *   java -jar app.jar low-stock --below 3
 *   java -jar app.jar simulate
 *
 * Or run with no arguments for the interactive menu.
 */
public class Main {

    private static final InventoryService service = new InventoryService();

    public static void main(String[] args) {
        // Initialize the database schema on every startup (safe: uses IF NOT EXISTS)
        try {
            Database.initSchema();
        } catch (SQLException e) {
            System.err.println("Error: could not initialize database — " + e.getMessage());
            return;
        }

        if (args.length == 0) {
            // No arguments → interactive menu
            runInteractive();
        } else {
            // Arguments provided → single command mode
            runCommand(args);
        }
    }

    // -------------------------------------------------------------------------
    // Single-command mode
    // -------------------------------------------------------------------------

    /**
     * Parses and executes a single command from the args array.
     * Used both by the CLI and by tests (via processCommand).
     */
    static void runCommand(String[] args) {
        String command = args[0].toLowerCase();
        try {
            switch (command) {
                case "add"        -> handleAdd(args);
                case "order"      -> handleOrder(args);
                case "cancel"     -> handleCancel(args);
                case "low-stock"  -> handleLowStock(args);
                case "simulate"   -> service.simulate();
                default           -> System.err.println(
                        "Error: unknown command '" + args[0] + "'. " +
                        "Available: add, order, cancel, low-stock, simulate");
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
        } catch (SQLException e) {
            System.err.println("Error: database problem — " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Interactive mode
    // -------------------------------------------------------------------------

    private static void runInteractive() {
        System.out.println("=== Inventory & Orders System ===");
        System.out.println("Commands: add, order, cancel, low-stock, simulate, exit");
        System.out.println("Type a command and press Enter. Type 'exit' to quit.");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase("exit")) {
                System.out.println("Goodbye!");
                break;
            }
            String[] parts = line.split("\\s+");
            runCommand(parts);
        }
    }

    // -------------------------------------------------------------------------
    // Command handlers
    // -------------------------------------------------------------------------

    /**
     * add <sku> <name> <price_paise> <stock>
     */
    private static void handleAdd(String[] args) throws IllegalArgumentException, SQLException {
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "Usage: add <sku> <name> <price_in_paise> <stock>");
        }
        String sku   = args[1];
        String name  = args[2];
        long   price = parseLong(args[3], "price");
        int    stock = parseInt(args[4], "stock");
        service.addProduct(sku, name, price, stock);
    }

    /**
     * order <customer> <sku>:<qty> [<sku>:<qty>…]
     */
    private static void handleOrder(String[] args) throws IllegalArgumentException, SQLException {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage: order <customer> <sku>:<qty> [<sku>:<qty>...]");
        }
        String customer = args[1];

        List<InventoryService.OrderRequest> lines = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String token = args[i];
            int colonIdx = token.lastIndexOf(':');
            if (colonIdx <= 0 || colonIdx == token.length() - 1) {
                throw new IllegalArgumentException(
                        "Order line must be in format sku:quantity, got: '" + token + "'");
            }
            String sku = token.substring(0, colonIdx);
            int    qty = parseInt(token.substring(colonIdx + 1), "quantity for " + sku);
            lines.add(new InventoryService.OrderRequest(sku, qty));
        }

        Order placed = service.placeOrder(customer, lines);
        System.out.printf("Order placed: id=%d, total=%d paise (₹%.2f)%n",
                placed.getId(), placed.getTotal(), placed.getTotal() / 100.0);
    }

    /**
     * cancel <order_id>
     */
    private static void handleCancel(String[] args) throws IllegalArgumentException, SQLException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: cancel <order_id>");
        }
        int orderId = parseInt(args[1], "order ID");
        service.cancelOrder(orderId);
    }

    /**
     * low-stock [--below N]
     */
    private static void handleLowStock(String[] args) throws IllegalArgumentException, SQLException {
        int threshold = 5; // default
        if (args.length >= 3 && "--below".equals(args[1])) {
            threshold = parseInt(args[2], "--below value");
        } else if (args.length == 2 && "--below".equals(args[1])) {
            throw new IllegalArgumentException("Usage: low-stock [--below <number>]");
        }

        List<Product> products = service.lowStock(threshold);
        if (products.isEmpty()) {
            System.out.printf("No products with stock below %d.%n", threshold);
        } else {
            System.out.printf("Products with stock below %d (lowest first):%n", threshold);
            System.out.printf("%-10s %-20s %10s %8s%n", "SKU", "Name", "Price(p)", "Stock");
            System.out.println("-".repeat(52));
            for (Product p : products) {
                System.out.printf("%-10s %-20s %10d %8d%n",
                        p.getSku(), p.getName(), p.getPrice(), p.getStock());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Parsing helpers — produce clear error messages for invalid input
    // -------------------------------------------------------------------------

    private static int parseInt(String value, String fieldName) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "'" + value + "' is not a valid integer for " + fieldName);
        }
    }

    private static long parseLong(String value, String fieldName) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "'" + value + "' is not a valid integer for " + fieldName);
        }
    }
}