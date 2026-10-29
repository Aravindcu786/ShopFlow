package com.shopflow.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.common.util.concurrent.ListenableFuture;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.animation.AccelerateDecelerateInterpolator;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Implements end-to-end multi-phase Face Enrollment:
 * - Phase 3: CameraX Integration (Preview, ImageAnalysis, ImageCapture)
 * - Phase 4: Face Detection (0 -> not detected, 1 -> continue, 2+ -> reject)
 * - Phase 5: Face Quality Validation (Size, Position, Laplacian blur, Brightness)
 * - Phase 6: Head Pose Detection (FRONT, LEFT, RIGHT)
 * - Phase 7: Pose Guidance (Real-time instructions)
 * - Phase 8: Stable Automatic Capture (Temporal stability gating)
 * - Phase 9: Three-View Face Enrollment (FRONT -> LEFT -> RIGHT -> COMPLETED)
 * - Phase 10: Android -> Flask Multipart Upload (POST /api/face/enroll)
 */
public class FaceEnrollmentActivity extends BaseActivity {

    private static final String BASE_URL = BuildConfig.BASE_URL;
    private static final int REQUIRED_STABLE_FRAMES = 5;

    private PreviewView viewCameraPreview;
    private View viewFaceGuide;
    private View viewScannerLaser;
    private View viewCaptureFlash;
    private LinearProgressIndicator progressEnrollmentSteps;
    private ObjectAnimator laserAnimator;

    private ImageView btnBack;
    private TextView tvInstruction;
    private TextView tvFaceDetectedLabel;
    private TextView tvPositionLabel;
    private TextView tvQualityLabel;
    private ImageView ivCheckFaceDetected;
    private ImageView ivCheckPosition;
    private ImageView ivCheckQuality;
    private TextView tvPoseFront;
    private TextView tvPoseLeft;
    private TextView tvPoseRight;
    private ProgressBar progressBarEnrollment;

    private String customerId = "CUST001";
    private String userEmail = "";

    private EnrollmentState currentState = EnrollmentState.FRONT;
    private int stableFramesCount = 0;
    private boolean isSubmitting = false;

    // Temporary captured view files (Phase 8 & 9)
    private File frontFile;
    private File leftFile;
    private File rightFile;

    private FaceFrameAnalyzer analyzer;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private final OkHttpClient httpClient = new OkHttpClient();

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission is required for face registration", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_face_enrollment);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.bio_bg));
        }

        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (insetsController != null) {
            insetsController.setAppearanceLightStatusBars(false);
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.face_enrollment_root), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            View header = findViewById(R.id.layoutHeader);
            if (header != null) {
                int extraTop = (int) (8 * getResources().getDisplayMetrics().density);
                header.setPadding(
                    header.getPaddingLeft(),
                    insets.top + extraTop,
                    header.getPaddingRight(),
                    header.getPaddingBottom()
                );
            }
            v.setPadding(0, 0, 0, insets.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(findViewById(R.id.face_enrollment_root));

        String passedId = getIntent().getStringExtra("CUSTOMER_ID");
        if (passedId != null && !passedId.isEmpty()) {
            customerId = passedId;
        }
        userEmail = getIntent().getStringExtra("USER_EMAIL");

        frontFile = new File(getCacheDir(), "front.jpg");
        leftFile = new File(getCacheDir(), "left.jpg");
        rightFile = new File(getCacheDir(), "right.jpg");

        cameraExecutor = Executors.newSingleThreadExecutor();

        initViews();
        setupListeners();
        updatePoseIndicators();
        checkCameraPermissionAndStart();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (laserAnimator != null) {
            laserAnimator.cancel();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        cleanupTempFiles();
    }

    private void initViews() {
        viewCameraPreview = findViewById(R.id.viewCameraPreview);
        viewFaceGuide = findViewById(R.id.viewFaceGuide);
        viewScannerLaser = findViewById(R.id.viewScannerLaser);
        viewCaptureFlash = findViewById(R.id.viewCaptureFlash);
        progressEnrollmentSteps = findViewById(R.id.progressEnrollmentSteps);

        btnBack = findViewById(R.id.btnBack);
        tvInstruction = findViewById(R.id.tvInstruction);
        tvFaceDetectedLabel = findViewById(R.id.tvFaceDetectedLabel);
        tvPositionLabel = findViewById(R.id.tvPositionLabel);
        tvQualityLabel = findViewById(R.id.tvQualityLabel);
        ivCheckFaceDetected = findViewById(R.id.ivCheckFaceDetected);
        ivCheckPosition = findViewById(R.id.ivCheckPosition);
        ivCheckQuality = findViewById(R.id.ivCheckQuality);
        tvPoseFront = findViewById(R.id.tvPoseFront);
        tvPoseLeft = findViewById(R.id.tvPoseLeft);
        tvPoseRight = findViewById(R.id.tvPoseRight);
        progressBarEnrollment = findViewById(R.id.progressBarEnrollment);

        startLaserScan();
    }

    private void startLaserScan() {
        if (viewScannerLaser != null) {
            viewScannerLaser.post(() -> {
                View parent = (View) viewScannerLaser.getParent();
                int parentHeight = parent != null ? parent.getHeight() : 0;
                if (parentHeight <= 0) parentHeight = 700;
                laserAnimator = ObjectAnimator.ofFloat(viewScannerLaser, "translationY", 0f, (float) (parentHeight - 40));
                laserAnimator.setDuration(2400);
                laserAnimator.setRepeatCount(ValueAnimator.INFINITE);
                laserAnimator.setRepeatMode(ValueAnimator.REVERSE);
                laserAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
                laserAnimator.start();
            });
        }
    }

    private void triggerCaptureFlash() {
        if (viewCaptureFlash != null) {
            viewCaptureFlash.setVisibility(View.VISIBLE);
            viewCaptureFlash.setAlpha(0.65f);
            viewCaptureFlash.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction(() -> viewCaptureFlash.setVisibility(View.GONE))
                    .start();
        }
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> showBypassPreventionDialog());

        // Prevent bypass on device back button / gesture
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                showBypassPreventionDialog();
            }
        });
    }

    /**
     * Prevent bypass: Registration is required to access the account.
     */
    private void showBypassPreventionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Face Registration Required")
                .setMessage("Face registration is required to continue.")
                .setPositiveButton("Continue Enrollment", (dialog, which) -> dialog.dismiss())
                .setNegativeButton("Log Out", (dialog, which) -> {
                    dialog.dismiss();
                    cleanupTempFiles();
                    Intent intent = new Intent(FaceEnrollmentActivity.this, LoginActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private void checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    /**
     * Phase 3: CameraX Integration
     * Connects Preview, ImageAnalysis, and ImageCapture.
     */
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 1. Preview Component
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewCameraPreview.getSurfaceProvider());

                // 2. ImageAnalysis Component
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analyzer = new FaceFrameAnalyzer(this::onFaceAnalyzed);
                analyzer.setTargetPose(PoseType.FRONT);
                imageAnalysis.setAnalyzer(cameraExecutor, analyzer);

                // 3. ImageCapture Component
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                // Select Front Camera (or back fallback)
                CameraSelector cameraSelector = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                        ? CameraSelector.DEFAULT_FRONT_CAMERA
                        : CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, imageCapture);

            } catch (Exception e) {
                android.util.Log.e("FaceEnrollment", "Error starting CameraX", e);
                Toast.makeText(this, "Failed to initialize camera", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Callback receiving face analysis results on every frame.
     */
    private void onFaceAnalyzed(FaceAnalysisResult result) {
        runOnUiThread(() -> {
            if (isSubmitting || currentState == EnrollmentState.COMPLETED) {
                return;
            }

            // Update Checklist UI & Guidance (Phases 4, 5, 7)
            updateChecklistUI(result);

            // Evaluate state machine progression & automatic capture (Phases 8 & 9)
            processEnrollmentStep(result);
        });
    }

    private void updateChecklistUI(FaceAnalysisResult result) {
        // Face Detected (0 -> ✗, 1 -> ✓, 2+ -> ✗)
        if (result.isFaceDetected() && result.getFaceCount() == 1) {
            ivCheckFaceDetected.setImageResource(R.drawable.ic_check);
            tvFaceDetectedLabel.setText("Face: OK");
            tvFaceDetectedLabel.setTextColor(ContextCompat.getColor(this, R.color.bio_emerald));
        } else {
            ivCheckFaceDetected.setImageResource(R.drawable.ic_close_red);
            tvFaceDetectedLabel.setText(result.getFaceCount() > 1 ? "Multiple" : "No Face");
            tvFaceDetectedLabel.setTextColor(ContextCompat.getColor(this, R.color.bio_text_muted));
        }

        // Position & Size status
        if (result.isPositionOk()) {
            ivCheckPosition.setImageResource(R.drawable.ic_check);
            tvPositionLabel.setText("Centered");
            tvPositionLabel.setTextColor(ContextCompat.getColor(this, R.color.bio_emerald));
        } else {
            ivCheckPosition.setImageResource(R.drawable.ic_close_red);
            tvPositionLabel.setText(result.getPositionMessage());
            tvPositionLabel.setTextColor(ContextCompat.getColor(this, R.color.bio_amber));
        }

        // Quality (Sharpness / Brightness) status
        if (result.isQualityOk()) {
            ivCheckQuality.setImageResource(R.drawable.ic_check);
            tvQualityLabel.setText("Optimal");
            tvQualityLabel.setTextColor(ContextCompat.getColor(this, R.color.bio_emerald));
        } else {
            ivCheckQuality.setImageResource(R.drawable.ic_close_red);
            tvQualityLabel.setText(result.getQualityMessage());
            tvQualityLabel.setTextColor(ContextCompat.getColor(this, R.color.bio_amber));
        }

        // Dynamic Face Guide Oval glow
        if (viewFaceGuide != null) {
            if (result.isFaceDetected() && result.isPositionOk() && result.isQualityOk()) {
                viewFaceGuide.setBackgroundResource(R.drawable.bg_face_guide_oval_success);
            } else if (result.isFaceDetected()) {
                viewFaceGuide.setBackgroundResource(R.drawable.bg_face_guide_oval_warning);
            } else {
                viewFaceGuide.setBackgroundResource(R.drawable.bg_face_guide_oval_default);
            }
        }

        // Phase 7: Real-Time Guidance Instruction
        if (result.getGuidanceMessage() != null && !result.getGuidanceMessage().isEmpty()) {
            tvInstruction.setText(result.getGuidanceMessage());
        }
    }

    /**
     * Phase 8 & 9: Stable Automatic Capture & Three-View Enrollment State Machine.
     */
    private void processEnrollmentStep(FaceAnalysisResult result) {
        boolean isValidFace = result.isFaceDetected()
                && result.getFaceCount() == 1
                && result.isPositionOk()
                && result.isQualityOk()
                && result.getFaceBitmap() != null;

        PoseType detectedPose = result.getPoseType();

        switch (currentState) {
            case FRONT:
                if (isValidFace && detectedPose == PoseType.FRONT) {
                    stableFramesCount++;
                    if (stableFramesCount >= REQUIRED_STABLE_FRAMES) {
                        // Capture front.jpg
                        boolean saved = saveBitmapToFile(result.getFaceBitmap(), frontFile);
                        if (saved) {
                            triggerCaptureFlash();
                            stableFramesCount = 0;
                            currentState = EnrollmentState.LEFT;
                            if (analyzer != null) analyzer.setTargetPose(PoseType.LEFT);
                            updatePoseIndicators();
                            Toast.makeText(this, "Front angle secured!", Toast.LENGTH_SHORT).show();
                        }
                    }
                } else {
                    stableFramesCount = Math.max(0, stableFramesCount - 1);
                }
                break;

            case LEFT:
                if (isValidFace && detectedPose == PoseType.LEFT) {
                    stableFramesCount++;
                    if (stableFramesCount >= REQUIRED_STABLE_FRAMES) {
                        // Capture left.jpg
                        boolean saved = saveBitmapToFile(result.getFaceBitmap(), leftFile);
                        if (saved) {
                            triggerCaptureFlash();
                            stableFramesCount = 0;
                            currentState = EnrollmentState.RIGHT;
                            if (analyzer != null) analyzer.setTargetPose(PoseType.RIGHT);
                            updatePoseIndicators();
                            Toast.makeText(this, "Left angle secured!", Toast.LENGTH_SHORT).show();
                        }
                    }
                } else {
                    stableFramesCount = Math.max(0, stableFramesCount - 1);
                }
                break;

            case RIGHT:
                if (isValidFace && detectedPose == PoseType.RIGHT) {
                    stableFramesCount++;
                    if (stableFramesCount >= REQUIRED_STABLE_FRAMES) {
                        // Capture right.jpg
                        boolean saved = saveBitmapToFile(result.getFaceBitmap(), rightFile);
                        if (saved) {
                            triggerCaptureFlash();
                            currentState = EnrollmentState.COMPLETED;
                            updatePoseIndicators();
                            Toast.makeText(this, "Right angle secured!", Toast.LENGTH_SHORT).show();

                            // Phase 10: Android -> Flask Upload
                            sendEnrollmentToBackend();
                        }
                    }
                } else {
                    stableFramesCount = Math.max(0, stableFramesCount - 1);
                }
                break;

            case COMPLETED:
                break;
        }
    }

    private void updatePoseIndicators() {
        int activeTextColor = ContextCompat.getColor(this, R.color.bio_cyan);
        int pendingTextColor = ContextCompat.getColor(this, R.color.bio_text_muted);
        int completedTextColor = ContextCompat.getColor(this, R.color.bio_emerald);

        switch (currentState) {
            case FRONT:
                tvPoseFront.setText("1. Front  ●");
                tvPoseFront.setBackgroundResource(R.drawable.bg_bio_chip_active);
                tvPoseFront.setTextColor(activeTextColor);

                tvPoseLeft.setText("2. Left  ○");
                tvPoseLeft.setBackgroundResource(R.drawable.bg_bio_chip_pending);
                tvPoseLeft.setTextColor(pendingTextColor);

                tvPoseRight.setText("3. Right  ○");
                tvPoseRight.setBackgroundResource(R.drawable.bg_bio_chip_pending);
                tvPoseRight.setTextColor(pendingTextColor);

                if (progressEnrollmentSteps != null) {
                    progressEnrollmentSteps.setProgressCompat(33, true);
                }
                break;

            case LEFT:
                tvPoseFront.setText("1. Front  ✓");
                tvPoseFront.setBackgroundResource(R.drawable.bg_bio_chip_completed);
                tvPoseFront.setTextColor(completedTextColor);

                tvPoseLeft.setText("2. Left  ●");
                tvPoseLeft.setBackgroundResource(R.drawable.bg_bio_chip_active);
                tvPoseLeft.setTextColor(activeTextColor);

                tvPoseRight.setText("3. Right  ○");
                tvPoseRight.setBackgroundResource(R.drawable.bg_bio_chip_pending);
                tvPoseRight.setTextColor(pendingTextColor);

                if (progressEnrollmentSteps != null) {
                    progressEnrollmentSteps.setProgressCompat(66, true);
                }
                break;

            case RIGHT:
                tvPoseFront.setText("1. Front  ✓");
                tvPoseFront.setBackgroundResource(R.drawable.bg_bio_chip_completed);
                tvPoseFront.setTextColor(completedTextColor);

                tvPoseLeft.setText("2. Left  ✓");
                tvPoseLeft.setBackgroundResource(R.drawable.bg_bio_chip_completed);
                tvPoseLeft.setTextColor(completedTextColor);

                tvPoseRight.setText("3. Right  ●");
                tvPoseRight.setBackgroundResource(R.drawable.bg_bio_chip_active);
                tvPoseRight.setTextColor(activeTextColor);

                if (progressEnrollmentSteps != null) {
                    progressEnrollmentSteps.setProgressCompat(90, true);
                }
                break;

            case COMPLETED:
                tvPoseFront.setText("1. Front  ✓");
                tvPoseFront.setBackgroundResource(R.drawable.bg_bio_chip_completed);
                tvPoseFront.setTextColor(completedTextColor);

                tvPoseLeft.setText("2. Left  ✓");
                tvPoseLeft.setBackgroundResource(R.drawable.bg_bio_chip_completed);
                tvPoseLeft.setTextColor(completedTextColor);

                tvPoseRight.setText("3. Right  ✓");
                tvPoseRight.setBackgroundResource(R.drawable.bg_bio_chip_completed);
                tvPoseRight.setTextColor(completedTextColor);

                if (progressEnrollmentSteps != null) {
                    progressEnrollmentSteps.setProgressCompat(100, true);
                }
                tvInstruction.setText("Encrypting & Registering with server...");
                break;
        }
    }

    private boolean saveBitmapToFile(Bitmap bitmap, File targetFile) {
        if (bitmap == null || targetFile == null) return false;
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos);
            fos.flush();
            return true;
        } catch (Exception e) {
            android.util.Log.e("FaceEnrollment", "Error saving image: " + targetFile.getName(), e);
            return false;
        }
    }

    /**
     * Phase 10: Android -> Flask Upload
     * Sends multipart/form-data with front.jpg, left.jpg, right.jpg, and customer_id
     * to POST /api/face/enroll.
     */
    private void sendEnrollmentToBackend() {
        if (!isNetworkAvailable()) {
            showNoInternetOverlay();
            return;
        }

        isSubmitting = true;
        progressBarEnrollment.setVisibility(View.VISIBLE);
        tvInstruction.setText("Validating images and generating embeddings...");

        try {
            if (frontFile == null || !frontFile.exists() ||
                leftFile == null || !leftFile.exists() ||
                rightFile == null || !rightFile.exists()) {
                Toast.makeText(this, "Captured images missing. Please retry.", Toast.LENGTH_SHORT).show();
                resetEnrollment();
                return;
            }

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("customer_id", customerId)
                    .addFormDataPart(
                            "front_image",
                            "front.jpg",
                            RequestBody.create(frontFile, MediaType.parse("image/jpeg"))
                    )
                    .addFormDataPart(
                            "left_image",
                            "left.jpg",
                            RequestBody.create(leftFile, MediaType.parse("image/jpeg"))
                    )
                    .addFormDataPart(
                            "right_image",
                            "right.jpg",
                            RequestBody.create(rightFile, MediaType.parse("image/jpeg"))
                    )
                    .build();

            Request request = new Request.Builder()
                    .url(BASE_URL + "/api/face/enroll")
                    .post(requestBody)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    android.util.Log.e("FaceEnrollment", "Failed to upload enrollment images", e);
                    runOnUiThread(() -> {
                        isSubmitting = false;
                        progressBarEnrollment.setVisibility(View.GONE);
                        Toast.makeText(
                                FaceEnrollmentActivity.this,
                                "Failed to connect to server: " + e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();
                        resetEnrollment();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : "";

                    runOnUiThread(() -> {
                        isSubmitting = false;
                        progressBarEnrollment.setVisibility(View.GONE);

                        try {
                            JSONObject result = new JSONObject(responseBody);
                            boolean success = result.optBoolean("success", false);
                            boolean faceEnrolled = result.optBoolean("face_enrolled", false);

                            if (success && faceEnrolled) {
                                cleanupTempFiles();

                                new SessionManager(FaceEnrollmentActivity.this).setFaceEnrolled(true);

                                Toast.makeText(
                                        FaceEnrollmentActivity.this,
                                        "Face registration successful!",
                                        Toast.LENGTH_LONG
                                ).show();

                                Intent intent = new Intent(FaceEnrollmentActivity.this, MainActivity.class);
                                intent.putExtra("CUSTOMER_ID", customerId);
                                intent.putExtra("USER_EMAIL", userEmail);
                                startActivity(intent);
                                finish();

                            } else {
                                String msg = result.optString("message", "Server face validation failed");
                                Toast.makeText(
                                        FaceEnrollmentActivity.this,
                                        msg,
                                        Toast.LENGTH_LONG
                                ).show();
                                resetEnrollment();
                            }

                        } catch (Exception e) {
                            android.util.Log.e("FaceEnrollment", "Error parsing server response", e);
                            Toast.makeText(
                                    FaceEnrollmentActivity.this,
                                    "Server response error",
                                    Toast.LENGTH_SHORT
                            ).show();
                            resetEnrollment();
                        }
                    });
                }
            });

        } catch (Exception e) {
            isSubmitting = false;
            progressBarEnrollment.setVisibility(View.GONE);
            android.util.Log.e("FaceEnrollment", "Error preparing enrollment upload", e);
            Toast.makeText(this, "Error preparing enrollment data", Toast.LENGTH_SHORT).show();
            resetEnrollment();
        }
    }

    private void resetEnrollment() {
        cleanupTempFiles();
        currentState = EnrollmentState.FRONT;
        stableFramesCount = 0;
        if (analyzer != null) {
            analyzer.setTargetPose(PoseType.FRONT);
        }
        updatePoseIndicators();
        tvInstruction.setText("Look straight at camera");
    }

    private void cleanupTempFiles() {
        if (frontFile != null && frontFile.exists()) frontFile.delete();
        if (leftFile != null && leftFile.exists()) leftFile.delete();
        if (rightFile != null && rightFile.exists()) rightFile.delete();
    }
}
