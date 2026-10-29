package com.shopflow.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;

/**
 * Base activity that provides universal real-time internet connectivity monitoring.
 * Displays a full-screen "No Internet" overlay with an image and informative message
 * whenever the device loses connection or starts offline.
 */
public abstract class BaseActivity extends AppCompatActivity {

    private View noInternetOverlay;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isCallbackRegistered = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupEdgeToEdge();
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        initNetworkCallback();
    }

    protected void setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insetsController != null) {
            insetsController.setAppearanceLightStatusBars(true);
        }
    }

    /**
     * Applies system bar window insets (status bar & navigation bar) to the root container view
     * to prevent UI from colliding or mixing with status bar icons and navigation bar.
     */
    protected void applySystemBarInsets(View containerView) {
        if (containerView == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(containerView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                v.getPaddingLeft(),
                insets.top,
                v.getPaddingRight(),
                insets.bottom
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(containerView);
    }

    @Override
    public void setContentView(@LayoutRes int layoutResID) {
        super.setContentView(layoutResID);
        attachNoInternetOverlay();
    }

    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        attachNoInternetOverlay();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        attachNoInternetOverlay();
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        if (noInternetOverlay == null) {
            attachNoInternetOverlay();
        }
    }

    private void attachNoInternetOverlay() {
        ViewGroup content = findViewById(android.R.id.content);
        if (content != null && noInternetOverlay == null) {
            LayoutInflater inflater = LayoutInflater.from(this);
            noInternetOverlay = inflater.inflate(R.layout.layout_no_internet, content, false);
            content.addView(noInternetOverlay);

            MaterialButton btnRetry = noInternetOverlay.findViewById(R.id.btnNoInternetRetry);
            MaterialButton btnSettings = noInternetOverlay.findViewById(R.id.btnNoInternetSettings);

            if (btnRetry != null) {
                btnRetry.setOnClickListener(v -> handleRetry());
            }

            if (btnSettings != null) {
                btnSettings.setOnClickListener(v -> NetworkUtils.openNetworkSettings(BaseActivity.this));
            }

            ViewCompat.setOnApplyWindowInsetsListener(noInternetOverlay, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(0, insets.top, 0, insets.bottom);
                return windowInsets;
            });
            ViewCompat.requestApplyInsets(noInternetOverlay);

            // Immediately check current network state
            if (!NetworkUtils.isInternetConnected(this)) {
                showNoInternetOverlay();
            } else {
                hideNoInternetOverlay();
            }
        }
    }

    private void initNetworkCallback() {
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                runOnUiThread(() -> {
                    hideNoInternetOverlay();
                    onNetworkRestored();
                });
            }

            @Override
            public void onLost(@NonNull Network network) {
                runOnUiThread(() -> {
                    if (!NetworkUtils.isInternetConnected(BaseActivity.this)) {
                        showNoInternetOverlay();
                        onNetworkLost();
                    }
                });
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
                boolean hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                runOnUiThread(() -> {
                    if (hasInternet) {
                        hideNoInternetOverlay();
                        onNetworkRestored();
                    } else if (!NetworkUtils.isInternetConnected(BaseActivity.this)) {
                        showNoInternetOverlay();
                        onNetworkLost();
                    }
                });
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerNetworkMonitor();
        // Check current connectivity
        if (!NetworkUtils.isInternetConnected(this)) {
            showNoInternetOverlay();
        } else {
            hideNoInternetOverlay();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterNetworkMonitor();
    }

    private void registerNetworkMonitor() {
        if (connectivityManager != null && !isCallbackRegistered && networkCallback != null) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
                isCallbackRegistered = true;
            } catch (Exception e) {
                isCallbackRegistered = false;
            }
        }
    }

    private void unregisterNetworkMonitor() {
        if (connectivityManager != null && isCallbackRegistered && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
            }
            isCallbackRegistered = false;
        }
    }

    private void handleRetry() {
        if (NetworkUtils.isInternetConnected(this)) {
            hideNoInternetOverlay();
            Toast.makeText(this, R.string.toast_internet_restored, Toast.LENGTH_SHORT).show();
            onNetworkRestored();
        } else {
            Toast.makeText(this, R.string.toast_still_no_internet, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Shows the "No Internet" overlay and blocks interaction with the screen.
     */
    public void showNoInternetOverlay() {
        if (noInternetOverlay != null) {
            noInternetOverlay.setVisibility(View.VISIBLE);
            noInternetOverlay.bringToFront();
        }
    }

    /**
     * Hides the "No Internet" overlay, allowing normal screen interaction.
     */
    public void hideNoInternetOverlay() {
        if (noInternetOverlay != null) {
            noInternetOverlay.setVisibility(View.GONE);
        }
    }

    /**
     * Convenience method to check whether internet is currently connected.
     */
    public boolean isNetworkAvailable() {
        return NetworkUtils.isInternetConnected(this);
    }

    /**
     * Called whenever network connection is confirmed or restored.
     * Subclasses may override this to trigger data refresh or resume flows.
     */
    protected void onNetworkRestored() {
    }

    /**
     * Called whenever network connection is lost.
     * Subclasses may override this to pause tasks if necessary.
     */
    protected void onNetworkLost() {
    }
}
