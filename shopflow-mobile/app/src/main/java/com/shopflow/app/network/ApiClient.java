package com.shopflow.app.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.shopflow.app.BuildConfig;
import com.shopflow.app.models.CustomerSession;
import com.shopflow.app.models.PaymentResult;
import com.shopflow.app.models.ShoppingCart;
import com.shopflow.app.models.ShoppingHistoryItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Centralized, reusable HTTP API client for ShopFlow backend communication.
 * All callbacks are dispatched on Android's main (UI) thread.
 */
public class ApiClient {

    private static final String TAG = "ApiClient";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static volatile ApiClient instance;
    private final OkHttpClient client;
    private final Handler mainHandler;
    private final String baseUrl;

    private ApiClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());

        String url = BuildConfig.BASE_URL;
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.baseUrl = url;
    }

    public static ApiClient getInstance() {
        if (instance == null) {
            synchronized (ApiClient.class) {
                if (instance == null) {
                    instance = new ApiClient();
                }
            }
        }
        return instance;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Fetch active shopping session for the given customer.
     * GET /api/shopping/customer/<customer_id>/active-session
     */
    public void getActiveShoppingSession(String customerId, final ApiCallback<CustomerSession> callback) {
        if (customerId == null || customerId.trim().isEmpty()) {
            dispatchError(callback, "Customer ID is required", 400);
            return;
        }

        String url = baseUrl + "/api/shopping/customer/" + customerId.trim() + "/active-session";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .tag("active-session")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "getActiveShoppingSession failed: " + e.getMessage());
                dispatchError(callback, "Unable to reach server: " + e.getMessage(), -1);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                int code = response.code();

                try {
                    JSONObject json = new JSONObject(responseBody);
                    boolean success = json.optBoolean("success", false);
                    boolean active = json.optBoolean("active", false);

                    if (response.isSuccessful() && success) {
                        if (!active || json.isNull("session")) {
                            dispatchSuccess(callback, null);
                        } else {
                            JSONObject sessionObj = json.optJSONObject("session");
                            CustomerSession session = CustomerSession.fromJson(sessionObj);
                            dispatchSuccess(callback, session);
                        }
                    } else {
                        String msg = json.optString("message", "Failed to retrieve active session");
                        dispatchError(callback, msg, code);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "getActiveShoppingSession parse error: " + e.getMessage());
                    dispatchError(callback, "Invalid server response", code);
                }
            }
        });
    }

    /**
     * Fetch full session state by session ID.
     * GET /api/shopping/session/<session_id>
     */
    public void getShoppingSession(String sessionId, final ApiCallback<CustomerSession> callback) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            dispatchError(callback, "Session ID is required", 400);
            return;
        }

        String url = baseUrl + "/api/shopping/session/" + sessionId.trim();
        Request request = new Request.Builder()
                .url(url)
                .get()
                .tag("session-detail")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                dispatchError(callback, "Unable to reach server: " + e.getMessage(), -1);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                int code = response.code();

                try {
                    JSONObject json = new JSONObject(responseBody);
                    boolean success = json.optBoolean("success", false);

                    if (response.isSuccessful() && success) {
                        JSONObject sessionObj = json.optJSONObject("session");
                        CustomerSession session = CustomerSession.fromJson(sessionObj);
                        dispatchSuccess(callback, session);
                    } else {
                        String msg = json.optString("message", "Failed to get session details");
                        dispatchError(callback, msg, code);
                    }
                } catch (Exception e) {
                    dispatchError(callback, "Invalid server response", code);
                }
            }
        });
    }

    /**
     * Fetch shopping cart contents for a session.
     * GET /api/shopping/session/<session_id>/cart
     */
    public void getCart(String sessionId, final ApiCallback<ShoppingCart> callback) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            dispatchError(callback, "Session ID is required", 400);
            return;
        }

        String url = baseUrl + "/api/shopping/session/" + sessionId.trim() + "/cart";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .tag("session-cart")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                dispatchError(callback, "Unable to reach server: " + e.getMessage(), -1);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                int code = response.code();

                try {
                    JSONObject json = new JSONObject(responseBody);
                    boolean success = json.optBoolean("success", false);

                    if (response.isSuccessful() && success) {
                        ShoppingCart cart = ShoppingCart.fromJson(json);
                        dispatchSuccess(callback, cart);
                    } else {
                        String msg = json.optString("message", "Failed to get cart");
                        dispatchError(callback, msg, code);
                    }
                } catch (Exception e) {
                    dispatchError(callback, "Invalid server response", code);
                }
            }
        });
    }

    /**
     * Process payment for an active shopping session.
     * POST /api/shopping/session/<session_id>/payment
     */
    public void payForSession(String sessionId, String paymentMethod, final ApiCallback<PaymentResult> callback) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            dispatchError(callback, "Session ID is required", 400);
            return;
        }

        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("payment_method", paymentMethod != null && !paymentMethod.isEmpty() ? paymentMethod : "APP");

            RequestBody body = RequestBody.create(bodyJson.toString(), JSON_MEDIA_TYPE);
            String url = baseUrl + "/api/shopping/session/" + sessionId.trim() + "/payment";

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .tag("session-payment")
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    dispatchError(callback, "Payment request failed: " + e.getMessage(), -1);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    int code = response.code();

                    try {
                        JSONObject json = new JSONObject(responseBody);
                        PaymentResult result = PaymentResult.fromJson(json);

                        if (response.isSuccessful() && result.isSuccess()) {
                            dispatchSuccess(callback, result);
                        } else {
                            String msg = result.getMessage().isEmpty() ? "Payment declined by server" : result.getMessage();
                            dispatchError(callback, msg, code);
                        }
                    } catch (Exception e) {
                        dispatchError(callback, "Invalid payment response from server", code);
                    }
                }
            });

        } catch (Exception e) {
            dispatchError(callback, "Failed to build payment request: " + e.getMessage(), 400);
        }
    }

    /**
     * Fetch shopping history for a customer.
     * GET /api/shopping/customer/<customer_id>/history
     */
    public void getShoppingHistory(String customerId, final ApiCallback<List<ShoppingHistoryItem>> callback) {
        if (customerId == null || customerId.trim().isEmpty()) {
            dispatchError(callback, "Customer ID is required", 400);
            return;
        }

        String url = baseUrl + "/api/shopping/customer/" + customerId.trim() + "/history";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .tag("customer-history")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                dispatchError(callback, "Unable to reach server: " + e.getMessage(), -1);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                int code = response.code();

                try {
                    JSONObject json = new JSONObject(responseBody);
                    boolean success = json.optBoolean("success", false);

                    if (response.isSuccessful() && success) {
                        List<ShoppingHistoryItem> list = new ArrayList<>();
                        JSONArray sessionsArr = json.optJSONArray("sessions");
                        if (sessionsArr != null) {
                            for (int i = 0; i < sessionsArr.length(); i++) {
                                JSONObject sObj = sessionsArr.optJSONObject(i);
                                if (sObj != null) {
                                    ShoppingHistoryItem item = ShoppingHistoryItem.fromJson(sObj);
                                    if (item != null) {
                                        list.add(item);
                                    }
                                }
                            }
                        }
                        dispatchSuccess(callback, list);
                    } else {
                        String msg = json.optString("message", "Failed to retrieve history");
                        dispatchError(callback, msg, code);
                    }
                } catch (Exception e) {
                    dispatchError(callback, "Invalid history response", code);
                }
            }
        });
    }

    /**
     * Cancel all calls tagged with the given tag.
     */
    public void cancelCallsWithTag(Object tag) {
        if (tag == null) return;
        for (Call call : client.dispatcher().queuedCalls()) {
            if (tag.equals(call.request().tag())) {
                call.cancel();
            }
        }
        for (Call call : client.dispatcher().runningCalls()) {
            if (tag.equals(call.request().tag())) {
                call.cancel();
            }
        }
    }

    private <T> void dispatchSuccess(final ApiCallback<T> callback, final T result) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onSuccess(result));
    }

    private <T> void dispatchError(final ApiCallback<T> callback, final String message, final int statusCode) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onError(message, statusCode));
    }
}
