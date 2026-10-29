package com.shopflow.app.network;

/**
 * Generic asynchronous callback interface for API operations.
 * Results and errors are guaranteed to be dispatched on the Android main (UI) thread.
 */
public interface ApiCallback<T> {
    void onSuccess(T result);
    void onError(String errorMessage, int statusCode);
}
