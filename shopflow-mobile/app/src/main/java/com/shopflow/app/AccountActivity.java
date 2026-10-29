package com.shopflow.app;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

public class AccountActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);
        applySystemBarInsets(findViewById(R.id.accountRoot));

        ImageView btnBack = findViewById(R.id.btnBackAccount);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        SessionManager sessionManager = new SessionManager(this);

        TextView tvInitials = findViewById(R.id.tvAccountInitials);
        TextView tvName = findViewById(R.id.tvAccountName);
        TextView tvCustomerId = findViewById(R.id.tvAccountCustomerId);
        TextView tvEmail = findViewById(R.id.tvAccountEmail);
        TextView tvPhone = findViewById(R.id.tvAccountPhone);
        TextView tvFaceStatus = findViewById(R.id.tvFaceRegistrationStatus);
        ImageView ivFaceIcon = findViewById(R.id.ivFaceStatusIcon);

        String firstName = sessionManager.getFirstName();
        String lastName = sessionManager.getLastName();
        String fullName = sessionManager.getFullName();
        String customerId = sessionManager.getCustomerId();
        String email = sessionManager.getUserEmail();
        String phone = sessionManager.getPhone();
        boolean faceEnrolled = sessionManager.isFaceEnrolled();

        // Calculate initials
        String initials = "";
        if (!firstName.isEmpty()) initials += firstName.substring(0, 1).toUpperCase();
        if (!lastName.isEmpty()) initials += lastName.substring(0, 1).toUpperCase();
        if (initials.isEmpty()) initials = "SF";

        if (tvInitials != null) tvInitials.setText(initials);
        if (tvName != null) tvName.setText(fullName.isEmpty() ? "Valued Customer" : fullName);
        if (tvCustomerId != null) tvCustomerId.setText("Customer ID: " + customerId);
        if (tvEmail != null) tvEmail.setText(email);
        if (tvPhone != null) tvPhone.setText(phone);

        if (tvFaceStatus != null) {
            if (faceEnrolled) {
                tvFaceStatus.setText("✓ Registered");
                tvFaceStatus.setTextColor(ContextCompat.getColor(this, R.color.success));
                if (ivFaceIcon != null) {
                    ivFaceIcon.setImageResource(R.drawable.ic_check);
                    ivFaceIcon.setColorFilter(ContextCompat.getColor(this, R.color.success));
                }
            } else {
                tvFaceStatus.setText("Pending Registration");
                tvFaceStatus.setTextColor(ContextCompat.getColor(this, R.color.error));
                if (ivFaceIcon != null) {
                    ivFaceIcon.setImageResource(R.drawable.ic_close_red);
                    ivFaceIcon.setColorFilter(ContextCompat.getColor(this, R.color.error));
                }
            }
        }
    }
}
