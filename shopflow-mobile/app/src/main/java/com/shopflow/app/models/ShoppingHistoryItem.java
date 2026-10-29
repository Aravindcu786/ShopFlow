package com.shopflow.app.models;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Model representing an item in a customer's past shopping visit history.
 */
public class ShoppingHistoryItem {

    private final String sessionId;
    private final String customerId;
    private final String status;
    private final String paymentStatus;
    private final String startedAt;
    private final String completedAt;
    private final int itemCount;
    private final double total;
    private final double subtotal;
    private final List<CartItem> items;

    public ShoppingHistoryItem(String sessionId, String customerId, String status,
                               String paymentStatus, String startedAt, String completedAt,
                               int itemCount, double total, double subtotal, List<CartItem> items) {
        this.sessionId = sessionId != null ? sessionId : "";
        this.customerId = customerId != null ? customerId : "";
        this.status = status != null ? status : "";
        this.paymentStatus = paymentStatus != null ? paymentStatus : "";
        this.startedAt = startedAt != null ? startedAt : "";
        this.completedAt = completedAt != null ? completedAt : "";
        this.itemCount = Math.max(0, itemCount);
        this.total = Math.max(0.0, total);
        this.subtotal = Math.max(0.0, subtotal);
        this.items = items != null ? items : Collections.emptyList();
    }

    public static ShoppingHistoryItem fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }

        String sessionId = json.optString("session_id", "");
        String customerId = json.optString("customer_id", "");
        String status = json.optString("status", "COMPLETED");
        String paymentStatus = json.optString("payment_status", "PAID");
        String startedAt = json.optString("started_at", "");
        String completedAt = json.optString("completed_at", "");

        int itemCount = json.optInt("item_count", 0);

        double total = 0.0;
        try {
            total = Double.parseDouble(json.optString("total", String.valueOf(json.optDouble("total", 0.0))));
        } catch (Exception ignored) {
            total = json.optDouble("total", 0.0);
        }

        double subtotal = 0.0;
        try {
            subtotal = Double.parseDouble(json.optString("subtotal", String.valueOf(json.optDouble("subtotal", 0.0))));
        } catch (Exception ignored) {
            subtotal = json.optDouble("subtotal", total);
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

        if (itemCount == 0 && !items.isEmpty()) {
            for (CartItem it : items) {
                itemCount += it.getQuantity();
            }
        }

        return new ShoppingHistoryItem(
                sessionId, customerId, status, paymentStatus,
                startedAt, completedAt, itemCount, total, subtotal, items
        );
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getStatus() {
        return status;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public String getCompletedAt() {
        return completedAt;
    }

    public int getItemCount() {
        return itemCount;
    }

    public double getTotal() {
        return total;
    }

    public double getSubtotal() {
        return subtotal;
    }

    public List<CartItem> getItems() {
        return items;
    }

    public String getFormattedTotal() {
        NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        return nf.format(total);
    }

    public String getFormattedDate() {
        String dateStr = !completedAt.isEmpty() ? completedAt : startedAt;
        if (dateStr == null || dateStr.isEmpty()) {
            return "Recent Visit";
        }
        try {
            String cleanDate = dateStr.replace("Z", "+00:00");
            SimpleDateFormat isoFormat;
            if (cleanDate.contains(".")) {
                isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.US);
            } else {
                isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            }
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = isoFormat.parse(cleanDate);
            if (date != null) {
                SimpleDateFormat outFormat = new SimpleDateFormat("dd MMMM yyyy", Locale.getDefault());
                return outFormat.format(date);
            }
        } catch (Exception ignored) {
        }
        return dateStr;
    }

    public String getSummaryText() {
        String itemUnit = itemCount == 1 ? "1 item" : itemCount + " items";
        return itemUnit + " purchased • Session #" + sessionId;
    }
}
