package com.shopflow.app;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

public class SettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applySystemBarInsets(findViewById(R.id.settingsRoot));

        ImageView btnBack = findViewById(R.id.btnBackSettings);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        // Account section items
        findViewById(R.id.itemPersonalInfo).setOnClickListener(v -> {
            Toast.makeText(this, "Personal information is up to date", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.itemChangePassword).setOnClickListener(v -> {
            Toast.makeText(this, "Password reset link sent to your registered email", Toast.LENGTH_SHORT).show();
        });

        // Security item
        findViewById(R.id.itemLoginSecurity).setOnClickListener(v -> {
            Toast.makeText(this, "Biometric Face Recognition is active", Toast.LENGTH_SHORT).show();
        });

        // App preferences
        findViewById(R.id.itemLanguage).setOnClickListener(v -> {
            Toast.makeText(this, "Language: English (US)", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.itemTheme).setOnClickListener(v -> {
            Toast.makeText(this, "Theme: System Default", Toast.LENGTH_SHORT).show();
        });

        // About section
        findViewById(R.id.itemAboutApp).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("About ShopFlow")
                    .setMessage("ShopFlow v1.0.0\nAutonomous AI Retail & Frictionless Checkout System.\n\nEnjoy checkout-free in-store shopping with automated biometric identity recognition.")
                    .setPositiveButton("Close", null)
                    .show();
        });

        findViewById(R.id.itemPrivacyPolicy).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Privacy Policy")
                    .setMessage("ShopFlow is committed to your privacy. Facial biometric data is strictly encrypted and used exclusively for your autonomous store identification.")
                    .setPositiveButton("Got it", null)
                    .show();
        });

        findViewById(R.id.itemTermsConditions).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Terms & Conditions")
                    .setMessage("By using ShopFlow, your selected items are automatically tracked and billed to your registered account upon exiting the store.")
                    .setPositiveButton("I agree", null)
                    .show();
        });
    }
}
