package com.shopflow.app;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Camera frame analyzer implementing:
 * - Phase 4: Face Detection (0 -> not detected, 1 -> continue, 2+ -> reject)
 * - Phase 5: Face Quality Validation (Size, Position, Sharpness/Laplacian blur, Brightness)
 * - Phase 6: Head Pose Detection (FRONT, LEFT, RIGHT via Euler Yaw/Pitch/Roll)
 * - Phase 7: Pose Guidance (Real-time comparison between required pose and detected pose)
 */
public class FaceFrameAnalyzer implements ImageAnalysis.Analyzer {

    public interface Listener {
        void onFaceAnalyzed(FaceAnalysisResult result);
    }

    private final Listener listener;
    private final FaceDetector detector;
    private volatile PoseType targetPose = PoseType.FRONT;
    private boolean isAnalyzing = false;

    public FaceFrameAnalyzer(Listener listener) {
        this.listener = listener;

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build();

        this.detector = FaceDetection.getClient(options);
    }

    public void setTargetPose(PoseType pose) {
        this.targetPose = pose;
    }

    @Override
    @SuppressLint("UnsafeOptInUsageError")
    public void analyze(@NonNull ImageProxy imageProxy) {
        if (isAnalyzing) {
            imageProxy.close();
            return;
        }

        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        isAnalyzing = true;
        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees);

        int frameWidth = inputImage.getWidth();
        int frameHeight = inputImage.getHeight();

        // Compute frame bitmap for Laplacian blur check & capture
        Bitmap frameBitmap = toBitmap(imageProxy);

        detector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    // Phase 4: Face Count Decision
                    if (faces == null || faces.isEmpty()) {
                        listener.onFaceAnalyzed(new FaceAnalysisResult(
                                0,
                                false,
                                false,
                                "Face not detected",
                                false,
                                "No face in view",
                                PoseType.UNKNOWN,
                                0.0f,
                                null,
                                null,
                                "Position your face in the camera view"
                        ));
                    } else if (faces.size() > 1) {
                        // 2+ faces -> Reject
                        listener.onFaceAnalyzed(new FaceAnalysisResult(
                                faces.size(),
                                false,
                                false,
                                "Multiple faces detected",
                                false,
                                "Reject: " + faces.size() + " faces",
                                PoseType.UNKNOWN,
                                0.0f,
                                null,
                                null,
                                "Multiple faces detected! Only one person allowed"
                        ));
                    } else {
                        // Exactly 1 face -> Continue to quality & pose analysis
                        Face face = faces.get(0);
                        analyzeFace(face, frameWidth, frameHeight, frameBitmap);
                    }
                })
                .addOnFailureListener(e -> {
                    listener.onFaceAnalyzed(new FaceAnalysisResult(
                            0,
                            false,
                            false,
                            "Detection error",
                            false,
                            "Error: " + e.getMessage(),
                            PoseType.UNKNOWN,
                            0.0f,
                            null,
                            null,
                            "Face detection error"
                    ));
                })
                .addOnCompleteListener(task -> {
                    isAnalyzing = false;
                    imageProxy.close();
                });
    }

    /**
     * Performs Phase 5 Quality Validation, Phase 6 Pose Estimation, and Phase 7 Pose Guidance.
     */
    private void analyzeFace(
            Face face,
            int frameWidth,
            int frameHeight,
            Bitmap frameBitmap
    ) {
        Rect bounds = face.getBoundingBox();

        // --- Phase 5: Position & Size Validation ---
        boolean positionOk = true;
        String positionMessage = "Position ✓";

        float faceRatio = (float) bounds.width() / (float) frameWidth;
        float centerX = bounds.exactCenterX();
        float centerY = bounds.exactCenterY();

        float offsetX = Math.abs(centerX - (frameWidth / 2.0f)) / (float) frameWidth;
        float offsetY = Math.abs(centerY - (frameHeight / 2.0f)) / (float) frameHeight;

        if (faceRatio < 0.22f) {
            positionOk = false;
            positionMessage = "Move closer";
        } else if (faceRatio > 0.85f) {
            positionOk = false;
            positionMessage = "Move back";
        } else if (offsetX > 0.22f || offsetY > 0.22f) {
            positionOk = false;
            positionMessage = "Move to center";
        }

        // --- Phase 5: Sharpness (Laplacian variance) & Brightness Validation ---
        boolean qualityOk = true;
        String qualityMessage = "Quality ✓";

        int avgBrightness = calculateFaceBrightness(frameBitmap, bounds);
        double laplacianVariance = calculateLaplacianVariance(frameBitmap, bounds);

        float roll = Math.abs(face.getHeadEulerAngleZ());
        float pitch = Math.abs(face.getHeadEulerAngleX());

        if (avgBrightness < 35) {
            qualityOk = false;
            qualityMessage = "Too dark";
        } else if (avgBrightness > 230) {
            qualityOk = false;
            qualityMessage = "Too bright";
        } else if (laplacianVariance < 30.0) {
            qualityOk = false;
            qualityMessage = "Too blurry - hold still";
        } else if (roll > 20.0f) {
            qualityOk = false;
            qualityMessage = "Keep head level";
        } else if (pitch > 22.0f) {
            qualityOk = false;
            qualityMessage = "Look straight ahead";
        }

        // --- Phase 6: Head Pose Classification ---
        // For front-facing camera (looking at user):
        // When user turns head to their LEFT (towards left shoulder), the face turns
        // towards the camera sensor's RIGHT, resulting in a POSITIVE Euler Y angle.
        // When user turns head to their RIGHT (towards right shoulder), the face turns
        // towards the camera sensor's LEFT, resulting in a NEGATIVE Euler Y angle.
        float eulerY = face.getHeadEulerAngleY();
        PoseType detectedPose;

        if (Math.abs(eulerY) <= 15.0f) {
            detectedPose = PoseType.FRONT;
        } else if (eulerY > 18.0f && eulerY <= 50.0f) {
            detectedPose = PoseType.LEFT;
        } else if (eulerY < -18.0f && eulerY >= -50.0f) {
            detectedPose = PoseType.RIGHT;
        } else {
            detectedPose = PoseType.UNKNOWN;
        }

        // --- Phase 7: Real-Time Pose Guidance ---
        String guidance;
        if (!positionOk) {
            guidance = positionMessage;
        } else if (!qualityOk) {
            guidance = qualityMessage;
        } else {
            // Compare required target pose vs detected pose
            if (targetPose == PoseType.FRONT) {
                if (detectedPose == PoseType.FRONT) {
                    guidance = "Hold still looking straight...";
                } else {
                    guidance = "Look straight at camera";
                }
            } else if (targetPose == PoseType.LEFT) {
                if (detectedPose == PoseType.LEFT) {
                    guidance = "Hold still turned left...";
                } else {
                    guidance = "Please turn your head LEFT";
                }
            } else if (targetPose == PoseType.RIGHT) {
                if (detectedPose == PoseType.RIGHT) {
                    guidance = "Hold still turned right...";
                } else {
                    guidance = "Please turn your head RIGHT";
                }
            } else {
                guidance = "Face registered!";
            }
        }

        listener.onFaceAnalyzed(new FaceAnalysisResult(
                1,
                true,
                positionOk,
                positionMessage,
                qualityOk,
                qualityMessage,
                detectedPose,
                eulerY,
                face,
                frameBitmap,
                guidance
        ));
    }

    /**
     * Phase 5: Laplacian variance blur calculation on the face crop.
     * Higher variance indicates sharper edges; low variance (< 30) indicates motion blur or defocus.
     */
    private double calculateLaplacianVariance(Bitmap bitmap, Rect faceBounds) {
        if (bitmap == null || faceBounds == null) return 100.0;

        int left = Math.max(0, faceBounds.left);
        int top = Math.max(0, faceBounds.top);
        int right = Math.min(bitmap.getWidth(), faceBounds.right);
        int bottom = Math.min(bitmap.getHeight(), faceBounds.bottom);

        int width = right - left;
        int height = bottom - top;
        if (width < 40 || height < 40) return 100.0;

        // Downsample for fast execution
        int step = Math.max(1, Math.min(width, height) / 80);
        int sampleW = width / step;
        int sampleH = height / step;

        int[] pixels = new int[width * height];
        try {
            bitmap.getPixels(pixels, 0, width, left, top, width, height);
        } catch (Exception e) {
            return 100.0;
        }

        int[][] gray = new int[sampleH][sampleW];
        for (int y = 0; y < sampleH; y++) {
            for (int x = 0; x < sampleW; x++) {
                int px = pixels[(y * step) * width + (x * step)];
                int r = (px >> 16) & 0xFF;
                int g = (px >> 8) & 0xFF;
                int b = px & 0xFF;
                gray[y][x] = (int) (0.299 * r + 0.587 * g + 0.114 * b);
            }
        }

        // Discrete Laplacian kernel
        double sum = 0;
        double sumSq = 0;
        int count = 0;

        for (int y = 1; y < sampleH - 1; y++) {
            for (int x = 1; x < sampleW - 1; x++) {
                int lap = gray[y - 1][x] + gray[y + 1][x] + gray[y][x - 1] + gray[y][x + 1] - 4 * gray[y][x];
                sum += lap;
                sumSq += (lap * lap);
                count++;
            }
        }

        if (count == 0) return 100.0;
        double mean = sum / count;
        return (sumSq / count) - (mean * mean);
    }

    /**
     * Phase 5: Brightness calculation inside face region.
     */
    private int calculateFaceBrightness(Bitmap bitmap, Rect faceBounds) {
        if (bitmap == null || faceBounds == null) return 128;

        int left = Math.max(0, faceBounds.left);
        int top = Math.max(0, faceBounds.top);
        int right = Math.min(bitmap.getWidth(), faceBounds.right);
        int bottom = Math.min(bitmap.getHeight(), faceBounds.bottom);

        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) return 128;

        int step = Math.max(1, Math.min(width, height) / 30);
        int total = 0;
        int count = 0;

        try {
            for (int y = top; y < bottom; y += step) {
                for (int x = left; x < right; x += step) {
                    int pixel = bitmap.getPixel(x, y);
                    int r = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    total += (int) (0.299 * r + 0.587 * g + 0.114 * b);
                    count++;
                }
            }
            return count > 0 ? total / count : 128;
        } catch (Exception e) {
            return 128;
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private Bitmap toBitmap(ImageProxy imageProxy) {
        try {
            Image image = imageProxy.getImage();
            if (image == null) return null;

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] nv21 = new byte[ySize + uSize + vSize];
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);

            YuvImage yuvImage = new YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    imageProxy.getWidth(),
                    imageProxy.getHeight(),
                    null
            );

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, imageProxy.getWidth(), imageProxy.getHeight()), 90, out);
            byte[] jpegBytes = out.toByteArray();

            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}
