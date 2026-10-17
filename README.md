# Inventory and Orders — Vayana Campus Assessment (Problem C)

A command-line Java program for a small shop that manages product inventory and customer orders against limited stock. Backed by SQLite via JDBC.

---

## How to Run

### Prerequisites
- Java 21 (included in IntelliJ via `.jdks/ms-21.0.11`)
- Maven (use IntelliJ's bundled Maven, or run `mvnw`)

### In IntelliJ IDEA

1. Open the `InventoryOrders` folder as an IntelliJ project.
2. Right-click `Main.java` → **Run 'Main.main()'**.
3. Edit the Run Configuration → **Program arguments** to pass a command.

### Run from Terminal (with Java on PATH)

```bash
# Build the fat JAR
mvn package -q

# Run a command
java -jar target/InventoryOrders-1.0-SNAPSHOT-all.jar add P1 Widget 5000 10
```

### Interactive mode (no arguments)

```
java -jar target/InventoryOrders-1.0-SNAPSHOT-all.jar
```

Type commands at the `>` prompt. Type `exit` to quit.

---

## Commands and Example Runs

### `add <sku> <name> <price_in_paise> <stock>`

Adds a new product, or increases its stock if the SKU already exists.

```
> add P1 Widget 5000 10
Added: SKU 'P1' (Widget) at 5000 paise, stock=10.

> add P1 Widget 5000 5
Updated: SKU 'P1' stock increased by 5.
```

### `order <customer> <sku>:<qty> [<sku>:<qty>...]`

Places an order of 1–5 lines. All-or-nothing: if any SKU lacks stock, nothing is placed.

```
> order Alice P1:2
Order placed: id=1, total=10000 paise (₹100.00)

> order Bob P1:999
Error: SKU 'P1' is short (has 13, need 999)
```

### `cancel <order_id>`

Cancels a PLACED order and restores its stock.

```
> cancel 1
Order 1 cancelled and stock restored.

> cancel 1
Error: Order 1 is already cancelled
```

### `low-stock [--below N]`

Lists products with stock below N (default 5), lowest stock first.

```
> low-stock
Products with stock below 5 (lowest first):
SKU        Name                 Price(p)    Stock
----------------------------------------------------

> low-stock --below 20
Products with stock below 20 (lowest first):
SKU        Name                 Price(p)    Stock
----------------------------------------------------
P1         Widget                   5000       13
```

### `simulate`

Starts 20 threads, each ordering 1 unit of P1 (stock=10). Exactly 10 succeed.

```
> simulate

Simulate: added P1 with stock=10. Starting 20 threads...

Simulate results:
  Succeeded : 10
  Failed    : 10
  P1 stock  : 0
  Rule holds: YES ✓
```

---

## Database Schema

```sql
CREATE TABLE IF NOT EXISTS products (
    sku     TEXT    PRIMARY KEY,
    name    TEXT    NOT NULL,
    price   INTEGER NOT NULL CHECK(price > 0),
    stock   INTEGER NOT NULL CHECK(stock >= 0)
);

CREATE TABLE IF NOT EXISTS orders (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    customer    TEXT    NOT NULL,
    created_at  TEXT    NOT NULL,
    status      TEXT    NOT NULL DEFAULT 'PLACED'
                        CHECK(status IN ('PLACED','CANCELLED')),
    total       INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS order_lines (
    order_id        INTEGER NOT NULL REFERENCES orders(id),
    sku             TEXT    NOT NULL REFERENCES products(sku),
    quantity        INTEGER NOT NULL CHECK(quantity > 0),
    price_at_order  INTEGER NOT NULL,
    PRIMARY KEY (order_id, sku)
);
```

**Design notes:**
- `price` and `price_at_order` are stored as **whole paise** (integers). ₹50.00 = 5000. This avoids floating-point rounding errors.
- `price_at_order` is copied from `products.price` at order time and **never updated**, so historical totals stay correct even after a price change.
- `CHECK(stock >= 0)` is a database-level guard; stock can never go negative even if there is a bug in application code.
- `PRAGMA foreign_keys = ON` is set on every connection to enforce referential integrity.

---

## How Concurrency Correctness Is Maintained

The `simulate` command starts 20 threads simultaneously using a `CountDownLatch` (a Java synchronisation tool that releases all threads at once). Each thread calls the same `placeOrder()` method used by the CLI.

The critical concurrency mechanism is **`BEGIN IMMEDIATE`** in `placeOrder()`:

1. Ordinary SQLite transactions (`BEGIN`) are deferred — they acquire only a read lock initially and upgrade to a write lock on the first write. This means two threads can both read `stock=5` and both decide to proceed, creating a race condition.

2. `BEGIN IMMEDIATE` acquires a **write lock immediately**, before any reads happen. SQLite only grants one write lock at a time. Every other thread attempting `BEGIN IMMEDIATE` concurrently will block and retry (up to 5,000 ms, configured via `PRAGMA busy_timeout = 5000`).

3. Inside the locked transaction, each thread reads the real current stock. If stock < quantity, the thread rolls back and reports "short". If stock is sufficient, it decrements stock and commits. The next thread then gets the lock, sees the decremented stock, and proceeds correctly.

4. This guarantees that when 20 threads try to buy 1 unit from a stock of 10, exactly 10 will acquire the lock while stock > 0 (succeed), and exactly 10 will acquire it when stock = 0 (fail). The final stock is always 0. The result is deterministic across every run.

---

## Running Tests

```bash
# In IntelliJ: right-click InventoryServiceTest.java → Run Tests
# Or via Maven:
mvn test
```

Tests use an isolated file `test_inventory.db` (set via `System.setProperty("test.db", ...)`) so they never affect production data.

---

## What I Would Do With Another Day

1. Add a `list-orders <customer>` command to view a customer's order history.
2. Add an `update-price <sku> <new_price>` command with validation.
3. Add connection pooling (HikariCP) for higher-concurrency production use.
4. Add end-to-end CLI tests that parse actual printed output.
5. Improve simulate to accept parameters (`--threads`, `--stock`, `--qty`) for flexible testing.
6. Add a `restock <sku> <qty>` command separate from `add` for clarity.
7. Export orders to CSV for reporting.

---

## Git History

```
git log --oneline
```

| Commit | Description |
|---|---|
| `feat: initialize project with schema and database layer` | pom.xml, Database.java, model POJOs |
| `feat: implement add, order, cancel, low-stock commands` | InventoryService.java, Main.java |
| `feat: add simulate with concurrent thread safety` | simulate() in InventoryService |
| `test: add 10 automated JUnit 5 tests` | InventoryServiceTest.java, DatabaseTestHelper.java |
| `docs: add README with schema and concurrency notes` | README.md |
