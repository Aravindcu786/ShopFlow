package com.shopflow.app.models;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Represents the customer's shopping cart for an active session.
 */
public class ShoppingCart {

    private final String sessionId;
    private final String customerId;
    private final List<CartItem> items;
    private final int itemCount;
    private final double subtotal;
    private final double total;
    private final double tax;
    private final double taxRate;

    public ShoppingCart(String sessionId, String customerId, List<CartItem> items,
                        int itemCount, double subtotal, double total, double tax, double taxRate) {
        this.sessionId = sessionId != null ? sessionId : "";
        this.customerId = customerId != null ? customerId : "";
        this.items = items != null ? items : Collections.emptyList();
        this.itemCount = Math.max(0, itemCount);
        this.subtotal = Math.max(0.0, subtotal);
        this.total = Math.max(0.0, total);
        this.tax = Math.max(0.0, tax);
        this.taxRate = Math.max(0.0, taxRate);
    }

    /**
     * Null-safe parser from backend JSON response.
     */
    public static ShoppingCart fromJson(JSONObject json) {
        if (json == null) {
            return empty();
        }

        String sessionId = json.optString("session_id", "");
        String customerId = json.optString("customer_id", "");
        int itemCount = json.optInt("item_count", 0);

        double subtotal = 0.0;
        try {
            subtotal = Double.parseDouble(json.optString("subtotal", String.valueOf(json.optDouble("subtotal", 0.0))));
        } catch (Exception ignored) {
            subtotal = json.optDouble("subtotal", 0.0);
        }

        double total = 0.0;
        try {
            total = Double.parseDouble(json.optString("total", String.valueOf(json.optDouble("total", 0.0))));
        } catch (Exception ignored) {
            total = json.optDouble("total", subtotal);
        }

        double tax = 0.0;
        try {
            tax = Double.parseDouble(json.optString("tax", String.valueOf(json.optDouble("tax", 0.0))));
        } catch (Exception ignored) {
            tax = json.optDouble("tax", 0.0);
        }

        double taxRate = 0.0;
        try {
            taxRate = Double.parseDouble(json.optString("tax_rate", String.valueOf(json.optDouble("tax_rate", 0.0))));
        } catch (Exception ignored) {
            taxRate = json.optDouble("tax_rate", 0.0);
        }

        List<CartItem> items = new ArrayList<>();
        JSONArray itemsArray = json.optJSONArray("items");
        if (itemsArray != null) {
            for (int i = 0; i < itemsArray.length(); i++) {
                JSONObject itemObj = itemsArray.optJSONObject(i);
                if (itemObj != null) {
                    items.add(CartItem.fromJson(itemObj));
                }
            }
        }

        // If itemCount was 0 in root, calculate from items list
        if (itemCount == 0 && !items.isEmpty()) {
            for (CartItem item : items) {
                itemCount += item.getQuantity();
            }
        }

        return new ShoppingCart(sessionId, customerId, items, itemCount, subtotal, total, tax, taxRate);
    }

    public static ShoppingCart empty() {
        return new ShoppingCart("", "", Collections.emptyList(), 0, 0.0, 0.0, 0.0, 0.0);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public List<CartItem> getItems() {
        return items;
    }

    public int getItemCount() {
        return itemCount;
    }

    public double getSubtotal() {
        return subtotal;
    }

    public double getTotal() {
        return total;
    }

    public double getTax() {
        return tax;
    }

    public double getTaxRate() {
        return taxRate;
    }

    public boolean isEmpty() {
        return itemCount == 0 || items.isEmpty();
    }

    public String getFormattedSubtotal() {
        NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        return nf.format(subtotal);
    }

    public String getFormattedTotal() {
        NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        return nf.format(total);
    }

    public String getFormattedTax() {
        NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        return nf.format(tax);
    }
}
