package org.example.model;

public class Order {
    private final int id;
    private final String customer;
    private final String createdAt;   // ISO-8601 string e.g. "2026-10-17T09:00:00"
    private final String status;      // "PLACED" or "CANCELLED"
    private final long total;         // in paise

    public Order(int id, String customer, String createdAt, String status, long total) {
        this.id = id;
        this.customer = customer;
        this.createdAt = createdAt;
        this.status = status;
        this.total = total;
    }

    public int getId()           { return id; }
    public String getCustomer()  { return customer; }
    public String getCreatedAt() { return createdAt; }
    public String getStatus()    { return status; }
    public long getTotal()       { return total; }

    @Override
    public String toString() {
        return String.format("Order{id=%d, customer='%s', status='%s', total=%d paise}",
                id, customer, status, total);
    }
}
