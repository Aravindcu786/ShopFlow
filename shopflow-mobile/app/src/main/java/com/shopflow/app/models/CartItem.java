package com.shopflow.app.models;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Represents an individual product item in a customer's shopping cart.
 */
public class CartItem {

    private final String productId;
    private final String name;
    private final int quantity;
    private final double unitPrice;
    private final double subtotal;
    private final String lastSensorId;

    public CartItem(String productId, String name, int quantity, double unitPrice, double subtotal, String lastSensorId) {
        this.productId = productId != null ? productId : "";
        this.name = name != null ? name : "";
        this.quantity = Math.max(0, quantity);
        this.unitPrice = unitPrice;
        this.subtotal = subtotal;
        this.lastSensorId = lastSensorId != null ? lastSensorId : "";
    }

    /**
     * Parse safely from backend JSON representation.
     */
    public static CartItem fromJson(JSONObject json) {
        if (json == null) {
            return new CartItem("", "", 0, 0.0, 0.0, "");
        }

        String productId = json.optString("product_id", "");
        String name = json.optString("name", productId);
        int quantity = json.optInt("quantity", 1);

        double unitPrice = 0.0;
        try {
            unitPrice = Double.parseDouble(json.optString("unit_price", String.valueOf(json.optDouble("unit_price", 0.0))));
        } catch (Exception ignored) {
            unitPrice = json.optDouble("unit_price", 0.0);
        }

        double subtotal = 0.0;
        try {
            subtotal = Double.parseDouble(json.optString("subtotal", String.valueOf(json.optDouble("subtotal", 0.0))));
        } catch (Exception ignored) {
            subtotal = json.optDouble("subtotal", unitPrice * quantity);
        }

        String lastSensorId = json.optString("last_sensor_id", "");

        return new CartItem(productId, name, quantity, unitPrice, subtotal, lastSensorId);
    }

    public String getProductId() {
        return productId;
    }

    public String getName() {
        return name.isEmpty() ? productId : name;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getUnitPrice() {
        return unitPrice;
    }

    public double getSubtotal() {
        return subtotal;
    }

    public String getLastSensorId() {
        return lastSensorId;
    }

    public String getFormattedUnitPrice() {
        NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        return nf.format(unitPrice);
    }

    public String getFormattedSubtotal() {
        NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        return nf.format(subtotal);
    }
}
