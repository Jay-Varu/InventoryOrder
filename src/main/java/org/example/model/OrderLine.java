package org.example.model;

public class OrderLine {
    private final int orderId;
    private final String sku;
    private final int quantity;
    private final long priceAtOrder;   // in paise, frozen at order time

    public OrderLine(int orderId, String sku, int quantity, long priceAtOrder) {
        this.orderId = orderId;
        this.sku = sku;
        this.quantity = quantity;
        this.priceAtOrder = priceAtOrder;
    }

    public int getOrderId()        { return orderId; }
    public String getSku()         { return sku; }
    public int getQuantity()       { return quantity; }
    public long getPriceAtOrder()  { return priceAtOrder; }

    @Override
    public String toString() {
        return String.format("OrderLine{orderId=%d, sku='%s', qty=%d, priceAtOrder=%d paise}",
                orderId, sku, quantity, priceAtOrder);
    }
}
