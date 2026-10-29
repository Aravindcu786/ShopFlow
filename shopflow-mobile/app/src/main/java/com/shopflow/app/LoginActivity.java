package com.shopflow.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Patterns;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

import java.io.IOException;

public class LoginActivity extends BaseActivity {

    private static final String BASE_URL = BuildConfig.BASE_URL;
    private final OkHttpClient client = new OkHttpClient();

    private TextInputLayout tilEmail, tilPassword;
    private TextInputEditText etEmail, etPassword;
    private MaterialButton btnLogin;
    private TextView tvRegisterLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        applySystemBarInsets(findViewById(R.id.scrollLogin));

        initViews();
        setupRegisterLinkText();
        setupListeners();
    }

    private void initViews() {
        tilEmail = findViewById(R.id.tilLoginEmail);
        tilPassword = findViewById(R.id.tilLoginPassword);
        etEmail = findViewById(R.id.etLoginEmail);
        etPassword = findViewById(R.id.etLoginPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvRegisterLink = findViewById(R.id.tvRegisterLink);
    }

    private void setupRegisterLinkText() {
        String text = getString(R.string.dont_have_account);
        SpannableString spannable = new SpannableString(text);
        int startIndex = text.indexOf("Register");
        if (startIndex != -1) {
            int endIndex = startIndex + "Register".length();
            spannable.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.primary)),
                    startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new StyleSpan(Typeface.BOLD),
                    startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvRegisterLink.setText(spannable);
    }

    private void setupListeners() {
        // Clear errors on typing
        etEmail.addTextChangedListener(new SimpleTextWatcher(tilEmail));
        etPassword.addTextChangedListener(new SimpleTextWatcher(tilPassword));

        // Log In button click (UI only)
        btnLogin.setOnClickListener(v -> handleLogin());

        // Navigate to Register Activity
        tvRegisterLink.setOnClickListener(v -> {
            Intent intent = new Intent(LoginActivity.this, RegisterActivity.class);
            startActivity(intent);
        });
    }

    private void handleLogin() {
        String email = etEmail.getText() != null ? etEmail.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

        boolean isValid = true;

        if (TextUtils.isEmpty(email)) {
            tilEmail.setError("Please enter your email");
            isValid = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError("Please enter a valid email address");
            isValid = false;
        } else {
            tilEmail.setError(null);
        }

        if (TextUtils.isEmpty(password)) {
            tilPassword.setError("Please enter your password");
            isValid = false;
        } else if (password.length() < 6) {
            tilPassword.setError("Password must be at least 6 characters");
            isValid = false;
        } else {
            tilPassword.setError(null);
        }

        if (isValid) {
            if (!isNetworkAvailable()) {
                showNoInternetOverlay();
                return;
            }
            loginWithServer(email, password);
        }
    }

    private void loginWithServer(String email, String password) {
        try {
            JSONObject json = new JSONObject();

            json.put("email", email);
            json.put("password", password);

            RequestBody body = RequestBody.create(
                    json.toString(),
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(BASE_URL + "/api/auth/login")
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build();

            btnLogin.setEnabled(false);

            client.newCall(request).enqueue(new Callback() {

                @Override
                public void onFailure(Call call, IOException e) {
                    android.util.Log.e("LoginActivity", "Connection failure", e);
                    runOnUiThread(() -> {
                        btnLogin.setEnabled(true);

                        Toast.makeText(
                                LoginActivity.this,
                                "Unable to connect to server",
                                Toast.LENGTH_LONG
                        ).show();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {

                    String responseBody = response.body() != null
                            ? response.body().string()
                            : "";

                    runOnUiThread(() -> {
                        try {
                            JSONObject result = new JSONObject(responseBody);

                            boolean success =
                                    result.optBoolean("success", false);

                            if (success) {

                                String customerId =
                                        result.optString("customer_id", "");

                                String firstName =
                                        result.optString("first_name", "");

                                String lastName =
                                        result.optString("last_name", "");

                                checkFaceEnrollment(customerId, firstName, lastName, email);

                            } else {
                                btnLogin.setEnabled(true);

                                String message =
                                        result.optString(
                                                "message",
                                                "Invalid email or password"
                                        );

                                Toast.makeText(
                                        LoginActivity.this,
                                        message,
                                        Toast.LENGTH_LONG
                                ).show();
                            }

                        } catch (Exception e) {
                            btnLogin.setEnabled(true);

                            Toast.makeText(
                                    LoginActivity.this,
                                    "Invalid server response",
                                    Toast.LENGTH_LONG
                                ).show();
                        }
                    });
                }
            });

        } catch (Exception e) {

            btnLogin.setEnabled(true);

            Toast.makeText(
                    this,
                    "Unable to create login request",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    /**
     * Checks face enrollment status after login succeeds.
     * Does NOT go to Home immediately.
     */
    private void checkFaceEnrollment(
            String customerId,
            String firstName,
            String lastName,
            String email
    ) {
        Request request = new Request.Builder()
                .url(BASE_URL + "/api/face/status/" + customerId)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                android.util.Log.e("LoginActivity", "Face status check failure", e);
                runOnUiThread(() -> {
                    btnLogin.setEnabled(true);
                    Toast.makeText(
                            LoginActivity.this,
                            "Unable to verify face enrollment status",
                            Toast.LENGTH_LONG
                    ).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() != null
                        ? response.body().string()
                        : "";

                runOnUiThread(() -> {
                    btnLogin.setEnabled(true);

                    try {
                        JSONObject result = new JSONObject(responseBody);
                        boolean faceEnrolled = result.optBoolean("face_enrolled", false);

                        if (faceEnrolled) {
                            openHome(customerId, email, firstName, lastName);
                        } else {
                            openFaceEnrollment(customerId, email, firstName, lastName);
                        }

                    } catch (Exception e) {
                        android.util.Log.e("LoginActivity", "Error parsing face status", e);
                        Toast.makeText(
                                LoginActivity.this,
                                "Invalid server response for face status",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });
            }
        });
    }

    private void openHome(String customerId, String email, String firstName, String lastName) {
        SessionManager sessionManager = new SessionManager(this);
        sessionManager.saveLoginSession(customerId, email, firstName, lastName, "", true);

        Toast.makeText(
                LoginActivity.this,
                "Welcome " + firstName,
                Toast.LENGTH_SHORT
        ).show();

        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        intent.putExtra("CUSTOMER_ID", customerId);
        intent.putExtra("USER_EMAIL", email);
        startActivity(intent);
        finish();
    }

    private void openFaceEnrollment(String customerId, String email, String firstName, String lastName) {
        SessionManager sessionManager = new SessionManager(this);
        sessionManager.saveLoginSession(customerId, email, firstName, lastName, "", false);

        Intent intent = new Intent(LoginActivity.this, FaceEnrollmentActivity.class);
        intent.putExtra("CUSTOMER_ID", customerId);
        intent.putExtra("USER_EMAIL", email);
        intent.putExtra("FIRST_NAME", firstName);
        startActivity(intent);
        finish();
    }

    private static class SimpleTextWatcher implements TextWatcher {
        private final TextInputLayout textInputLayout;

        public SimpleTextWatcher(TextInputLayout textInputLayout) {
            this.textInputLayout = textInputLayout;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (textInputLayout.getError() != null) {
                textInputLayout.setError(null);
            }
        }

        @Override
        public void afterTextChanged(Editable s) {}
    }
}
