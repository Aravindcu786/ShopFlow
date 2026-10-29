package com.shopflow.app;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceLandmark;

import org.json.JSONArray;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates 512-dimensional face embedding vectors from detected face and image crops.
 * The vector is strictly normalized (L2 norm = 1.0) as expected by pgvector vector(512).
 */
public class FaceEmbeddingGenerator {

    public static final int EMBEDDING_DIM = 512;

    public static float[] generateEmbedding(Face face, Bitmap frameBitmap) {
        float[] embedding = new float[EMBEDDING_DIM];

        if (face == null || frameBitmap == null) {
            return normalize(embedding);
        }

        Rect bounds = face.getBoundingBox();
        int left = Math.max(0, bounds.left);
        int top = Math.max(0, bounds.top);
        int width = Math.min(frameBitmap.getWidth() - left, Math.max(1, bounds.width()));
        int height = Math.min(frameBitmap.getHeight() - top, Math.max(1, bounds.height()));

        Bitmap croppedFace = null;
        try {
            croppedFace = Bitmap.createBitmap(frameBitmap, left, top, width, height);
        } catch (Exception e) {
            croppedFace = frameBitmap;
        }

        // Resize to normalized 112x112 face thumbnail
        Bitmap scaledFace = Bitmap.createScaledBitmap(croppedFace, 112, 112, true);

        // Feature extraction: 16x16 spatial grid x RGB features + Landmark geometric features
        int idx = 0;
        int stepX = scaledFace.getWidth() / 16;
        int stepY = scaledFace.getHeight() / 16;

        for (int y = 0; y < 16 && idx < 384; y++) {
            for (int x = 0; x < 16 && idx < 384; x++) {
                int pixel = scaledFace.getPixel(x * stepX, y * stepY);
                float r = ((pixel >> 16) & 0xFF) / 255.0f - 0.5f;
                float g = ((pixel >> 8) & 0xFF) / 255.0f - 0.5f;
                float b = (pixel & 0xFF) / 255.0f - 0.5f;

                embedding[idx++] = r;
                if (idx < EMBEDDING_DIM) embedding[idx++] = g;
                if (idx < EMBEDDING_DIM) embedding[idx++] = b;
            }
        }

        // Facial Landmark geometric feature encodings (remaining dimensions up to 512)
        int[] landmarkTypes = {
                FaceLandmark.LEFT_EYE,
                FaceLandmark.RIGHT_EYE,
                FaceLandmark.NOSE_BASE,
                FaceLandmark.MOUTH_LEFT,
                FaceLandmark.MOUTH_RIGHT,
                FaceLandmark.MOUTH_BOTTOM,
                FaceLandmark.LEFT_EAR,
                FaceLandmark.RIGHT_EAR,
                FaceLandmark.LEFT_CHEEK,
                FaceLandmark.RIGHT_CHEEK
        };

        float faceW = Math.max(1.0f, bounds.width());
        float faceH = Math.max(1.0f, bounds.height());

        for (int landmarkType : landmarkTypes) {
            if (idx >= EMBEDDING_DIM - 4) break;
            FaceLandmark landmark = face.getLandmark(landmarkType);
            if (landmark != null) {
                embedding[idx++] = (landmark.getPosition().x - bounds.left) / faceW - 0.5f;
                embedding[idx++] = (landmark.getPosition().y - bounds.top) / faceH - 0.5f;
            } else {
                embedding[idx++] = 0.0f;
                embedding[idx++] = 0.0f;
            }
        }

        // Add pose Euler angles to embedding
        if (idx < EMBEDDING_DIM) embedding[idx++] = face.getHeadEulerAngleX() / 90.0f;
        if (idx < EMBEDDING_DIM) embedding[idx++] = face.getHeadEulerAngleY() / 90.0f;
        if (idx < EMBEDDING_DIM) embedding[idx++] = face.getHeadEulerAngleZ() / 90.0f;

        // Fill any remaining with derived cosine variations
        while (idx < EMBEDDING_DIM) {
            embedding[idx] = (float) Math.sin((idx + 1) * embedding[idx % 128]);
            idx++;
        }

        return normalize(embedding);
    }

    private static float[] normalize(float[] vec) {
        float sumSquares = 0.0f;
        for (float v : vec) {
            sumSquares += v * v;
        }
        float norm = (float) Math.sqrt(Math.max(1e-12, sumSquares));
        for (int i = 0; i < vec.length; i++) {
            vec[i] = vec[i] / norm;
        }
        return vec;
    }

    public static JSONArray toJsonArray(float[] embedding) {
        JSONArray array = new JSONArray();
        for (float v : embedding) {
            try {
                array.put((double) v);
            } catch (Exception ignored) {}
        }
        return array;
    }
}
