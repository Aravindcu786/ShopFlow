package com.shopflow.app.models;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Represents a customer's shopping session state as reported by the backend.
 */
public class CustomerSession {

    private final String sessionId;
    private final String customerId;
    private final String status;
    private final String paymentStatus;
    private final boolean exitAllowed;
    private final String startedAt;
    private final String updatedAt;
    private final String completedAt;
    private final String exitDetectedAt;
    private final int itemCount;
    private final double subtotal;
    private final double total;
    private final ShoppingCart cart;

    public CustomerSession(String sessionId, String customerId, String status,
                           String paymentStatus, boolean exitAllowed, String startedAt,
                           String updatedAt, String completedAt, String exitDetectedAt,
                           int itemCount, double subtotal, double total, ShoppingCart cart) {
        this.sessionId = sessionId != null ? sessionId : "";
        this.customerId = customerId != null ? customerId : "";
        this.status = status != null ? status : "INACTIVE";
        this.paymentStatus = paymentStatus != null ? paymentStatus : "PENDING";
        this.exitAllowed = exitAllowed;
        this.startedAt = startedAt != null ? startedAt : "";
        this.updatedAt = updatedAt != null ? updatedAt : "";
        this.completedAt = completedAt != null ? completedAt : "";
        this.exitDetectedAt = exitDetectedAt != null ? exitDetectedAt : "";
        this.itemCount = Math.max(0, itemCount);
        this.subtotal = Math.max(0.0, subtotal);
        this.total = Math.max(0.0, total);
        this.cart = cart != null ? cart : ShoppingCart.empty();
    }

    /**
     * Null-safe parser for backend session JSON.
     */
    public static CustomerSession fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }

        String sessionId = json.optString("session_id", "");
        String customerId = json.optString("customer_id", "");
        String status = json.optString("status", "ACTIVE");
        String paymentStatus = json.optString("payment_status", "PENDING");
        boolean exitAllowed = json.optBoolean("exit_allowed", false);
        String startedAt = json.optString("started_at", "");
        String updatedAt = json.optString("updated_at", "");
        String completedAt = json.optString("completed_at", "");
        String exitDetectedAt = json.optString("exit_detected_at", "");

        ShoppingCart cart = null;
        if (json.has("cart")) {
            cart = ShoppingCart.fromJson(json.optJSONObject("cart"));
        }

        int itemCount = cart != null ? cart.getItemCount() : json.optInt("item_count", 0);

        double subtotal = 0.0;
        if (cart != null) {
            subtotal = cart.getSubtotal();
        } else {
            try {
                subtotal = Double.parseDouble(json.optString("subtotal", String.valueOf(json.optDouble("subtotal", 0.0))));
            } catch (Exception ignored) {
                subtotal = json.optDouble("subtotal", 0.0);
            }
        }

        double total = 0.0;
        if (cart != null) {
            total = cart.getTotal();
        } else {
            try {
                total = Double.parseDouble(json.optString("total", String.valueOf(json.optDouble("total", 0.0))));
            } catch (Exception ignored) {
                total = json.optDouble("total", subtotal);
            }
        }

        return new CustomerSession(
                sessionId, customerId, status, paymentStatus, exitAllowed,
                startedAt, updatedAt, completedAt, exitDetectedAt,
                itemCount, subtotal, total, cart
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

    public boolean isExitAllowed() {
        return exitAllowed;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public String getCompletedAt() {
        return completedAt;
    }

    public String getExitDetectedAt() {
        return exitDetectedAt;
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

    public ShoppingCart getCart() {
        return cart;
    }

    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status)
                || "PENDING_PAYMENT".equalsIgnoreCase(status)
                || ("PAID".equalsIgnoreCase(status) && !isCompleted());
    }

    public boolean isPaid() {
        return "PAID".equalsIgnoreCase(paymentStatus) || "PAID".equalsIgnoreCase(status);
    }

    public boolean isPendingPayment() {
        return !isPaid();
    }

    public boolean isCompleted() {
        return "COMPLETED".equalsIgnoreCase(status);
    }

    public String getFormattedTotal() {
        NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        return nf.format(total);
    }

    public String getFormattedSubtotal() {
        NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("en", "IN"));
        return nf.format(subtotal);
    }

    /**
     * Formats ISO timestamp to human-friendly local time (e.g. "10:15 AM").
     */
    public String getFormattedStartTime() {
        if (startedAt == null || startedAt.isEmpty()) {
            return "Just now";
        }
        try {
            // Backend formats as isoformat(), e.g. 2026-09-29T15:30:00 or with milliseconds
            String cleanDate = startedAt.replace("Z", "+00:00");
            SimpleDateFormat isoFormat;
            if (cleanDate.contains(".")) {
                isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.US);
            } else {
                isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            }
            isoFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = isoFormat.parse(cleanDate);
            if (date != null) {
                SimpleDateFormat outFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
                return outFormat.format(date);
            }
        } catch (Exception ignored) {
        }
        return startedAt;
    }
}
