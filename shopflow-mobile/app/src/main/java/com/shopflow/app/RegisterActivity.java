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

public class RegisterActivity extends BaseActivity {

    private static final String BASE_URL = BuildConfig.BASE_URL;
    private final OkHttpClient client = new OkHttpClient();

    private TextInputLayout tilFirstName, tilLastName, tilEmail, tilPassword, tilConfirmPassword;
    private TextInputEditText etFirstName, etLastName, etEmail, etPassword, etConfirmPassword;
    private MaterialButton btnRegister;
    private TextView tvAlreadyRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);
        applySystemBarInsets(findViewById(R.id.scrollRegister));

        initViews();
        setupLoginLinkText();
        setupListeners();
    }

    private void initViews() {
        tilFirstName = findViewById(R.id.tilFirstName);
        tilLastName = findViewById(R.id.tilLastName);
        tilEmail = findViewById(R.id.tilEmail);
        tilPassword = findViewById(R.id.tilPassword);
        tilConfirmPassword = findViewById(R.id.tilConfirmPassword);

        etFirstName = findViewById(R.id.etFirstName);
        etLastName = findViewById(R.id.etLastName);
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);

        btnRegister = findViewById(R.id.btnRegister);
        tvAlreadyRegistered = findViewById(R.id.tvAlreadyRegistered);
    }

    private void setupLoginLinkText() {
        String text = getString(R.string.already_registered);
        SpannableString spannable = new SpannableString(text);
        int startIndex = text.indexOf("Log In");
        if (startIndex != -1) {
            int endIndex = startIndex + "Log In".length();
            spannable.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.primary)),
                    startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new StyleSpan(Typeface.BOLD),
                    startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvAlreadyRegistered.setText(spannable);
    }

    private void setupListeners() {
        // Clear errors while typing
        etFirstName.addTextChangedListener(new SimpleTextWatcher(tilFirstName));
        etLastName.addTextChangedListener(new SimpleTextWatcher(tilLastName));
        etEmail.addTextChangedListener(new SimpleTextWatcher(tilEmail));
        etPassword.addTextChangedListener(new SimpleTextWatcher(tilPassword));
        etConfirmPassword.addTextChangedListener(new SimpleTextWatcher(tilConfirmPassword));

        // Register button click (UI only)
        btnRegister.setOnClickListener(v -> handleRegistration());

        // Already registered text - navigate back to Login
        tvAlreadyRegistered.setOnClickListener(v -> {
            finish(); // Returns to LoginActivity
        });
    }

    private void handleRegistration() {
        String firstName = etFirstName.getText() != null ? etFirstName.getText().toString().trim() : "";
        String lastName = etLastName.getText() != null ? etLastName.getText().toString().trim() : "";
        String email = etEmail.getText() != null ? etEmail.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";
        String confirmPassword = etConfirmPassword.getText() != null ? etConfirmPassword.getText().toString().trim() : "";

        boolean isValid = true;

        if (TextUtils.isEmpty(firstName)) {
            tilFirstName.setError("Please enter your first name");
            isValid = false;
        } else {
            tilFirstName.setError(null);
        }

        if (TextUtils.isEmpty(lastName)) {
            tilLastName.setError("Please enter your last name");
            isValid = false;
        } else {
            tilLastName.setError(null);
        }

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
            tilPassword.setError("Please enter a password");
            isValid = false;
        } else if (password.length() < 6) {
            tilPassword.setError("Password must be at least 6 characters");
            isValid = false;
        } else {
            tilPassword.setError(null);
        }

        if (TextUtils.isEmpty(confirmPassword)) {
            tilConfirmPassword.setError("Please confirm your password");
            isValid = false;
        } else if (!confirmPassword.equals(password)) {
            tilConfirmPassword.setError("Passwords do not match");
            isValid = false;
        } else {
            tilConfirmPassword.setError(null);
        }

        if (isValid) {
            if (!isNetworkAvailable()) {
                showNoInternetOverlay();
                return;
            }
            registerWithServer(firstName, lastName, email, password);
        }
    }

    private void registerWithServer(
            String firstName,
            String lastName,
            String email,
            String password
    ) {
        try {
            JSONObject json = new JSONObject();

            json.put("first_name", firstName);
            json.put("last_name", lastName);
            json.put("email", email);
            json.put("password", password);

            RequestBody body = RequestBody.create(
                    json.toString(),
                    MediaType.parse("application/json")
            );

            Request request = new Request.Builder()
                    .url(BASE_URL + "/api/customers/register")
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build();

            btnRegister.setEnabled(false);

            client.newCall(request).enqueue(new Callback() {

                @Override
                public void onFailure(Call call, IOException e) {
                    android.util.Log.e("RegisterActivity", "Connection failure", e);
                    runOnUiThread(() -> {
                        btnRegister.setEnabled(true);

                        Toast.makeText(
                                RegisterActivity.this,
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
                        btnRegister.setEnabled(true);

                        try {
                            JSONObject result = new JSONObject(responseBody);

                            boolean success = result.optBoolean("success", false);

                            if (success) {

                                String customerId =
                                        result.optString("customer_id", "");

                                Toast.makeText(
                                        RegisterActivity.this,
                                        "Account created: " + customerId,
                                        Toast.LENGTH_LONG
                                ).show();

                                finish();

                            } else {

                                String message =
                                        result.optString(
                                                "message",
                                                "Registration failed"
                                        );

                                Toast.makeText(
                                        RegisterActivity.this,
                                        message,
                                        Toast.LENGTH_LONG
                                ).show();
                            }

                        } catch (Exception e) {

                            Toast.makeText(
                                    RegisterActivity.this,
                                    "Invalid server response",
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            });

        } catch (Exception e) {

            btnRegister.setEnabled(true);

            Toast.makeText(
                    this,
                    "Unable to create registration request",
                    Toast.LENGTH_LONG
            ).show();
        }
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
