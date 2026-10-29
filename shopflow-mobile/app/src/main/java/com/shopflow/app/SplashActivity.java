package com.shopflow.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public class SplashActivity extends BaseActivity {

    private static final int SPLASH_DURATION_MS = 2000; // 2 seconds delay
    private final Handler splashHandler = new Handler(Looper.getMainLooper());
    private boolean isTimerFinished = false;
    private boolean hasNavigated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        applySystemBarInsets(findViewById(R.id.splashRoot));

        splashHandler.postDelayed(() -> {
            isTimerFinished = true;
            if (isNetworkAvailable()) {
                proceedToNextScreen();
            } else {
                showNoInternetOverlay();
            }
        }, SPLASH_DURATION_MS);
    }

    @Override
    protected void onNetworkRestored() {
        super.onNetworkRestored();
        if (isTimerFinished && !hasNavigated) {
            proceedToNextScreen();
        }
    }

    private void proceedToNextScreen() {
        if (hasNavigated) return;
        hasNavigated = true;

        SessionManager sessionManager = new SessionManager(this);
        Intent intent;
        if (sessionManager.isLoggedIn()) {
            if (sessionManager.isFaceEnrolled()) {
                intent = new Intent(SplashActivity.this, MainActivity.class);
                intent.putExtra("CUSTOMER_ID", sessionManager.getCustomerId());
                intent.putExtra("USER_EMAIL", sessionManager.getUserEmail());
            } else {
                intent = new Intent(SplashActivity.this, FaceEnrollmentActivity.class);
                intent.putExtra("CUSTOMER_ID", sessionManager.getCustomerId());
                intent.putExtra("USER_EMAIL", sessionManager.getUserEmail());
                intent.putExtra("FIRST_NAME", sessionManager.getFirstName());
            }
        } else {
            intent = new Intent(SplashActivity.this, LoginActivity.class);
        }

        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        splashHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
