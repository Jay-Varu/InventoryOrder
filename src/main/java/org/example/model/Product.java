package org.example.model;

public class Product {
    private final String sku;
    private final String name;
    private final long price;   // in paise
    private final int stock;

    public Product(String sku, String name, long price, int stock) {
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

    public String getSku()   { return sku; }
    public String getName()  { return name; }
    public long getPrice()   { return price; }
    public int getStock()    { return stock; }

    @Override
    public String toString() {
        return String.format("Product{sku='%s', name='%s', price=%d paise, stock=%d}",
                sku, name, price, stock);
    }
}
