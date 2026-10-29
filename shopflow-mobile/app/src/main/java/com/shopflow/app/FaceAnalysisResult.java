package com.shopflow.app;

import android.graphics.Bitmap;

import com.google.mlkit.vision.face.Face;

public class FaceAnalysisResult {
    private final int faceCount;
    private final boolean faceDetected;
    private final boolean positionOk;
    private final String positionMessage;
    private final boolean qualityOk;
    private final String qualityMessage;
    private final PoseType poseType;
    private final float eulerY;
    private final Face face;
    private final Bitmap faceBitmap;
    private final String guidanceMessage;

    public FaceAnalysisResult(
            int faceCount,
            boolean faceDetected,
            boolean positionOk,
            String positionMessage,
            boolean qualityOk,
            String qualityMessage,
            PoseType poseType,
            float eulerY,
            Face face,
            Bitmap faceBitmap,
            String guidanceMessage
    ) {
        this.faceCount = faceCount;
        this.faceDetected = faceDetected;
        this.positionOk = positionOk;
        this.positionMessage = positionMessage;
        this.qualityOk = qualityOk;
        this.qualityMessage = qualityMessage;
        this.poseType = poseType;
        this.eulerY = eulerY;
        this.face = face;
        this.faceBitmap = faceBitmap;
        this.guidanceMessage = guidanceMessage;
    }

    public FaceAnalysisResult(
            boolean faceDetected,
            boolean positionOk,
            String positionMessage,
            boolean qualityOk,
            String qualityMessage,
            PoseType poseType,
            float eulerY,
            Face face,
            Bitmap faceBitmap
    ) {
        this(faceDetected ? 1 : 0, faceDetected, positionOk, positionMessage, qualityOk, qualityMessage,
                poseType, eulerY, face, faceBitmap, "");
    }

    public int getFaceCount() {
        return faceCount;
    }

    public boolean isFaceDetected() {
        return faceDetected;
    }

    public boolean isPositionOk() {
        return positionOk;
    }

    public String getPositionMessage() {
        return positionMessage;
    }

    public boolean isQualityOk() {
        return qualityOk;
    }

    public String getQualityMessage() {
        return qualityMessage;
    }

    public PoseType getPoseType() {
        return poseType;
    }

    public float getEulerY() {
        return eulerY;
    }

    public Face getFace() {
        return face;
    }

    public Bitmap getFaceBitmap() {
        return faceBitmap;
    }

    public String getGuidanceMessage() {
        return guidanceMessage;
    }
}
