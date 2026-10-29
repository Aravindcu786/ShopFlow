package com.shopflow.app.models;

import org.json.JSONObject;

/**
 * Encapsulates the response from the server payment endpoint.
 */
public class PaymentResult {

    private final boolean success;
    private final String sessionId;
    private final String paymentStatus;
    private final boolean exitAllowed;
    private final String message;

    public PaymentResult(boolean success, String sessionId, String paymentStatus, boolean exitAllowed, String message) {
        this.success = success;
        this.sessionId = sessionId != null ? sessionId : "";
        this.paymentStatus = paymentStatus != null ? paymentStatus : "";
        this.exitAllowed = exitAllowed;
        this.message = message != null ? message : "";
    }

    public static PaymentResult fromJson(JSONObject json) {
        if (json == null) {
            return new PaymentResult(false, "", "", false, "Empty response from server");
        }

        boolean success = json.optBoolean("success", false);
        String sessionId = json.optString("session_id", "");
        String paymentStatus = json.optString("payment_status", "");
        boolean exitAllowed = json.optBoolean("exit_allowed", false);
        String message = json.optString("message", success ? "Payment successful" : "Payment failed");

        return new PaymentResult(success, sessionId, paymentStatus, exitAllowed, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public boolean isExitAllowed() {
        return exitAllowed;
    }

    public String getMessage() {
        return message;
    }
}
