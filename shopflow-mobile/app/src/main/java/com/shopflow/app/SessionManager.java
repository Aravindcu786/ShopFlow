package com.shopflow.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manages user authentication credentials, customer identity, and optional last-known UI cache.
 * Note: The backend server is the authoritative source of truth for all live shopping sessions,
 * cart contents, pricing, payment confirmation, exit permissions, and visit history.
 */
public class SessionManager {

    private static final String PREF_NAME = "shopflow_user_session";

    // User Profile & Authentication Keys (Authoritative locally for client auth)
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_CUSTOMER_ID = "customer_id";
    private static final String KEY_USER_EMAIL = "user_email";
    private static final String KEY_FIRST_NAME = "first_name";
    private static final String KEY_LAST_NAME = "last_name";
    private static final String KEY_PHONE = "phone";
    private static final String KEY_FACE_ENROLLED = "face_enrolled";

    // Optional Last-Known Session Cache (Non-authoritative, updated from server)
    private static final String KEY_IS_SHOPPING_ACTIVE = "is_shopping_active";
    private static final String KEY_ACTIVE_SESSION_ID = "active_session_id";
    private static final String KEY_ACTIVE_CART_COUNT = "active_cart_count";
    private static final String KEY_ACTIVE_CART_TOTAL = "active_cart_total";
    private static final String KEY_ACTIVE_START_TIME = "active_start_time";
    private static final String KEY_SESSION_STATUS = "session_status";
    private static final String KEY_PAYMENT_STATUS = "payment_status";
    private static final String KEY_PAYMENT_PENDING = "payment_pending";
    private static final String KEY_EXIT_ALLOWED = "exit_allowed";
    private static final String KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp";

    // Customer Statistics & Last Visit Cache (Updated from server history)
    private static final String KEY_TOTAL_VISITS = "total_visits";
    private static final String KEY_TOTAL_SPENT = "total_spent";
    private static final String KEY_ITEMS_PURCHASED = "items_purchased";
    private static final String KEY_LAST_VISIT_DATE = "last_visit_date";
    private static final String KEY_LAST_VISIT_ITEMS = "last_visit_items";
    private static final String KEY_LAST_VISIT_AMOUNT = "last_visit_amount";

    private final SharedPreferences prefs;

    public SessionManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Saves primary login session details.
     */
    public void saveLoginSession(String customerId, String email, String firstName, boolean faceEnrolled) {
        saveLoginSession(customerId, email, firstName, "", "", faceEnrolled);
    }

    /**
     * Saves complete login session details without injecting fake shopping data.
     */
    public void saveLoginSession(String customerId, String email, String firstName, String lastName, String phone, boolean faceEnrolled) {
        prefs.edit()
                .putBoolean(KEY_IS_LOGGED_IN, true)
                .putString(KEY_CUSTOMER_ID, customerId != null ? customerId : "")
                .putString(KEY_USER_EMAIL, email != null ? email : "")
                .putString(KEY_FIRST_NAME, firstName != null ? firstName : "")
                .putString(KEY_LAST_NAME, lastName != null ? lastName : "")
                .putString(KEY_PHONE, phone != null && !phone.isEmpty() ? phone : "")
                .putBoolean(KEY_FACE_ENROLLED, faceEnrolled)
                .apply();
    }

    public void setFaceEnrolled(boolean faceEnrolled) {
        prefs.edit().putBoolean(KEY_FACE_ENROLLED, faceEnrolled).apply();
    }

    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    public boolean isFaceEnrolled() {
        return prefs.getBoolean(KEY_FACE_ENROLLED, false);
    }

    public String getCustomerId() {
        return prefs.getString(KEY_CUSTOMER_ID, "");
    }

    public String getUserEmail() {
        return prefs.getString(KEY_USER_EMAIL, "");
    }

    public String getFirstName() {
        return prefs.getString(KEY_FIRST_NAME, "");
    }

    public String getLastName() {
        return prefs.getString(KEY_LAST_NAME, "");
    }

    public String getFullName() {
        String first = getFirstName();
        String last = getLastName();
        if (last.isEmpty()) return first;
        if (first.isEmpty()) return last;
        return first + " " + last;
    }

    public String getPhone() {
        return prefs.getString(KEY_PHONE, "");
    }

    public void setPhone(String phone) {
        prefs.edit().putString(KEY_PHONE, phone).apply();
    }

    // --- Active Session Snapshot Caching (Phase 12) ---

    /**
     * Cache a snapshot of the live shopping session returned by the server.
     */
    public void saveActiveSessionSnapshot(String sessionId, int itemCount, float total,
                                          String startTime, String status, String paymentStatus,
                                          boolean exitAllowed) {
        prefs.edit()
                .putBoolean(KEY_IS_SHOPPING_ACTIVE, true)
                .putString(KEY_ACTIVE_SESSION_ID, sessionId != null ? sessionId : "")
                .putInt(KEY_ACTIVE_CART_COUNT, Math.max(0, itemCount))
                .putFloat(KEY_ACTIVE_CART_TOTAL, Math.max(0.0f, total))
                .putString(KEY_ACTIVE_START_TIME, startTime != null ? startTime : "")
                .putString(KEY_SESSION_STATUS, status != null ? status : "ACTIVE")
                .putString(KEY_PAYMENT_STATUS, paymentStatus != null ? paymentStatus : "PENDING")
                .putBoolean(KEY_PAYMENT_PENDING, !"PAID".equalsIgnoreCase(paymentStatus))
                .putBoolean(KEY_EXIT_ALLOWED, exitAllowed)
                .putLong(KEY_LAST_SYNC_TIMESTAMP, System.currentTimeMillis())
                .apply();
    }

    /**
     * Clears cached active session snapshot when backend reports no active session.
     */
    public void clearActiveSessionSnapshot() {
        prefs.edit()
                .putBoolean(KEY_IS_SHOPPING_ACTIVE, false)
                .putString(KEY_ACTIVE_SESSION_ID, "")
                .putInt(KEY_ACTIVE_CART_COUNT, 0)
                .putFloat(KEY_ACTIVE_CART_TOTAL, 0.0f)
                .putString(KEY_ACTIVE_START_TIME, "")
                .putString(KEY_SESSION_STATUS, "INACTIVE")
                .putString(KEY_PAYMENT_STATUS, "")
                .putBoolean(KEY_PAYMENT_PENDING, false)
                .putBoolean(KEY_EXIT_ALLOWED, false)
                .putLong(KEY_LAST_SYNC_TIMESTAMP, System.currentTimeMillis())
                .apply();
    }

    // --- Backward-Compatible Getters / Setters ---

    public boolean isShoppingSessionActive() {
        return prefs.getBoolean(KEY_IS_SHOPPING_ACTIVE, false);
    }

    public void setShoppingSessionActive(boolean active) {
        prefs.edit().putBoolean(KEY_IS_SHOPPING_ACTIVE, active).apply();
    }

    public String getActiveSessionId() {
        return prefs.getString(KEY_ACTIVE_SESSION_ID, "");
    }

    public int getActiveCartCount() {
        return prefs.getInt(KEY_ACTIVE_CART_COUNT, 0);
    }

    public float getActiveCartTotal() {
        return prefs.getFloat(KEY_ACTIVE_CART_TOTAL, 0.0f);
    }

    public String getActiveStartTime() {
        return prefs.getString(KEY_ACTIVE_START_TIME, "");
    }

    public boolean isPaymentPending() {
        return prefs.getBoolean(KEY_PAYMENT_PENDING, false);
    }

    public void setPaymentCompleted(boolean completed) {
        prefs.edit().putBoolean(KEY_PAYMENT_PENDING, !completed).apply();
    }

    public boolean isExitAllowed() {
        return prefs.getBoolean(KEY_EXIT_ALLOWED, false);
    }

    public long getLastSyncTimestamp() {
        return prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L);
    }

    public void setLastSyncTimestamp(long timestamp) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, timestamp).apply();
    }

    // --- Customer Statistics & History Cache ---

    public void saveCustomerStats(int totalVisits, float totalSpent, int itemsPurchased) {
        prefs.edit()
                .putInt(KEY_TOTAL_VISITS, Math.max(0, totalVisits))
                .putFloat(KEY_TOTAL_SPENT, Math.max(0.0f, totalSpent))
                .putInt(KEY_ITEMS_PURCHASED, Math.max(0, itemsPurchased))
                .apply();
    }

    public void saveLastVisit(String date, int items, float amount) {
        prefs.edit()
                .putString(KEY_LAST_VISIT_DATE, date != null ? date : "")
                .putInt(KEY_LAST_VISIT_ITEMS, Math.max(0, items))
                .putFloat(KEY_LAST_VISIT_AMOUNT, Math.max(0.0f, amount))
                .apply();
    }

    public int getTotalVisits() {
        return prefs.getInt(KEY_TOTAL_VISITS, 0);
    }

    public float getTotalSpent() {
        return prefs.getFloat(KEY_TOTAL_SPENT, 0.0f);
    }

    public int getItemsPurchased() {
        return prefs.getInt(KEY_ITEMS_PURCHASED, 0);
    }

    public String getLastVisitDate() {
        return prefs.getString(KEY_LAST_VISIT_DATE, "");
    }

    public int getLastVisitItems() {
        return prefs.getInt(KEY_LAST_VISIT_ITEMS, 0);
    }

    public float getLastVisitAmount() {
        return prefs.getFloat(KEY_LAST_VISIT_AMOUNT, 0.0f);
    }

    /**
     * Clears all session data on logout.
     */
    public void clearSession() {
        prefs.edit().clear().apply();
    }
}
