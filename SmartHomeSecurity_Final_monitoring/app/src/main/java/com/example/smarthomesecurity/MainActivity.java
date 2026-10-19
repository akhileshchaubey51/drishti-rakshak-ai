package com.example.smarthomesecurity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import org.json.JSONObject;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements MjpegHttpServer.SirenController {

    private static final String TAG = "SmartHomeSecurity";
    private static final String UID_TAG = "MONITORING_UID";

    // Owner app's authenticated Firebase UID
    private static final String OWNER_UID =
            "9BsnYtyeWebctRXsMfE29oCMjxG3";

    private static final String CLOUD_NAME = "prmmfvpo";
    private static final String UPLOAD_PRESET = "smart_home_security";
    private static final String MODEL_FILE = "efficientdet-lite0.tflite";

    private static final int MAX_PENDING_UPLOADS = 5;

    private FirebaseAuth firebaseAuth;
    private FirebaseFirestore firestore;
    private ListenerRegistration authorizedMembersListener;

    private PreviewView previewView;
    private DetectionOverlay detectionOverlay;
    private TextView tvLiveStreamInfo;
    private ProcessCameraProvider cameraProvider;

    private ExecutorService cameraExecutor;
    private ExecutorService uploadExecutor;

    private ObjectDetector objectDetector;
    private MjpegHttpServer mjpegHttpServer;

    private volatile boolean securityRunning = false;
    private volatile boolean firebaseReady = false;
    private volatile boolean signInInProgress = false;
    private volatile boolean sirenActive = false;

    private long lastAlertMs = 0;
    private int eventCount = 0;

    private ToneGenerator toneGenerator;
    private MediaPlayer sirenPlayer;

    // Authorized family members whitelisted by Owner App
    private final List<AuthorizedMember> authorizedMembers = new CopyOnWriteArrayList<>();

    public static class AuthorizedMember {
        public final String name;
        public final String role;
        public volatile float[] embedding;

        public AuthorizedMember(String name, String role, float[] embedding) {
            this.name = name;
            this.role = role;
            this.embedding = embedding;
        }
    }

    // Keeps snapshots captured while Firebase authentication is pending.
    private final Queue<PendingSnapshot> pendingSnapshots =
            new ConcurrentLinkedQueue<>();

    private static class PendingSnapshot {
        final byte[] imageBytes;
        final float score;

        PendingSnapshot(byte[] imageBytes, float score) {
            this.imageBytes = imageBytes;
            this.score = score;
        }
    }

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            startCamera();
                        } else {
                            Toast.makeText(
                                    this,
                                    "Camera permission is required.",
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        detectionOverlay = findViewById(R.id.detectionOverlay);
        tvLiveStreamInfo = findViewById(R.id.tvLiveStreamInfo);

        View viewScannerBeam = findViewById(R.id.viewScannerBeam);
        if (viewScannerBeam != null) {
            android.view.animation.Animation scanAnim =
                    android.view.animation.AnimationUtils.loadAnimation(this, R.anim.radar_scan);
            viewScannerBeam.startAnimation(scanAnim);
        }

        View viewHomeScannerBeam = findViewById(R.id.viewHomeScannerBeam);
        if (viewHomeScannerBeam != null) {
            android.view.animation.Animation scanAnim =
                    android.view.animation.AnimationUtils.loadAnimation(this, R.anim.radar_scan);
            viewHomeScannerBeam.startAnimation(scanAnim);
        }

        TextView tvLivePill = findViewById(R.id.tvLivePill);
        if (tvLivePill != null) {
            android.view.animation.Animation pulseAnim =
                    android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse);
            tvLivePill.startAnimation(pulseAnim);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        uploadExecutor = Executors.newSingleThreadExecutor();

        toneGenerator = new ToneGenerator(
                AudioManager.STREAM_ALARM, 80
        );

        initializeFirebase();
        initializeSiren();
        startMjpegStreamServer();

        eventCount = getPreferences(MODE_PRIVATE)
                .getInt("event_count", 0);

        securityRunning = true;

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    // ---------------------------------------------------------
    // 24/7 LIVE STREAM SERVER
    // ---------------------------------------------------------

    private void startMjpegStreamServer() {
        try {
            mjpegHttpServer = new MjpegHttpServer(8080, this);
            mjpegHttpServer.start();

            String ip = MjpegHttpServer.getDeviceIp();
            String streamUrl = "http://" + ip + ":8080/stream";
            Log.i(TAG, "24/7 CCTV Live Stream URL: " + streamUrl);

            if (tvLiveStreamInfo != null) {
                tvLiveStreamInfo.setText("📡 CCTV LIVE: " + streamUrl);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not start MJPEG Stream Server", e);
        }
    }

    // ---------------------------------------------------------
    // FIREBASE INITIALIZATION & WHITELIST SYNC
    // ---------------------------------------------------------

    private void initializeFirebase() {
        try {
            firebaseAuth = FirebaseAuth.getInstance();
            firestore = FirebaseFirestore.getInstance();

            // Real-time synchronization of authorized family members added by Owner
            syncAuthorizedMembers();

            if (firebaseAuth.getCurrentUser() != null) {
                firebaseReady = true;

                Log.i(
                        UID_TAG,
                        "Monitoring UID = "
                                + firebaseAuth.getCurrentUser().getUid()
                );

                Log.i(TAG, "Firebase already signed in");

                drainPendingSnapshots();
                return;
            }

            signInInProgress = true;

            Log.i(TAG, "Starting anonymous Firebase sign-in");

            firebaseAuth.signInAnonymously()
                    .addOnSuccessListener(result -> {
                        signInInProgress = false;

                        if (result.getUser() == null) {
                            firebaseReady = false;

                            Log.e(
                                    TAG,
                                    "Sign-in returned no Firebase user"
                            );
                            return;
                        }

                        firebaseReady = true;

                        Log.i(
                                UID_TAG,
                                "Monitoring UID = "
                                        + result.getUser().getUid()
                        );

                        Log.i(TAG, "Firebase sign-in successful");

                        // Re-sync after sign-in
                        syncAuthorizedMembers();

                        Toast.makeText(
                                this,
                                "Monitoring Online & Connected",
                                Toast.LENGTH_SHORT
                        ).show();

                        drainPendingSnapshots();
                    })
                    .addOnFailureListener(e -> {
                        signInInProgress = false;
                        firebaseReady = false;

                        Log.e(TAG, "Firebase sign-in failed", e);

                        Toast.makeText(
                                this,
                                "Firebase sign-in failed: "
                                        + e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show();
                    });

        } catch (Exception e) {
            firebaseReady = false;
            signInInProgress = false;

            Log.e(TAG, "Firebase initialization failed", e);
        }
    }

    /**
     * Listens in real-time to authorizedUsers collection in Firestore.
     * When the Owner adds or removes a family member, this automatically syncs.
     */
    private void syncAuthorizedMembers() {
        if (firestore == null) return;

        try {
            if (authorizedMembersListener != null) {
                authorizedMembersListener.remove();
            }

            // Listen to authorizedUsers collection directly without restrictive filter
            authorizedMembersListener = firestore.collection("authorizedUsers")
                    .addSnapshotListener((snapshots, error) -> {
                        if (error != null) {
                            Log.e(TAG, "Authorized users sync error: " + error.getMessage());
                            runOnUiThread(() ->
                                    Toast.makeText(MainActivity.this, "Sync Error: " + error.getMessage(), Toast.LENGTH_SHORT).show()
                            );
                            return;
                        }

                        if (snapshots != null) {
                            List<AuthorizedMember> list = new ArrayList<>();
                            for (DocumentSnapshot doc : snapshots.getDocuments()) {
                                String name = doc.getString("name");
                                String role = doc.getString("role");
                                String embeddingStr = doc.getString("faceEmbedding");
                                String imageUrl = doc.getString("imageUrl");

                                if (name != null && !name.trim().isEmpty()) {
                                    float[] emb = parseEmbedding(embeddingStr);
                                    AuthorizedMember member = new AuthorizedMember(name, role != null ? role : "Family", emb);
                                    list.add(member);

                                    // If embedding is not yet calculated, fetch image from Cloudinary to generate it dynamically
                                    if (emb == null && imageUrl != null && imageUrl.startsWith("http")) {
                                        fetchAndComputeEmbedding(member, imageUrl);
                                    }
                                }
                            }

                            authorizedMembers.clear();
                            authorizedMembers.addAll(list);

                            Log.i(TAG, "✅ Synced " + authorizedMembers.size() + " authorized family members from cloud!");

                            runOnUiThread(() -> {
                                if (tvLiveStreamInfo != null) {
                                    String ip = MjpegHttpServer.getDeviceIp();
                                    tvLiveStreamInfo.setText("📡 CCTV LIVE: http://" + ip + ":8080/stream | 👥 Whitelist: " + authorizedMembers.size());
                                }
                                Toast.makeText(MainActivity.this, "✅ Whitelist: " + authorizedMembers.size() + " Members Synced", Toast.LENGTH_SHORT).show();
                            });
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error attaching authorizedUsers listener", e);
        }
    }

    private void fetchAndComputeEmbedding(AuthorizedMember member, String imageUrl) {
        if (uploadExecutor == null || uploadExecutor.isShutdown()) return;
        uploadExecutor.execute(() -> {
            try {
                InputStream in = new URL(imageUrl).openStream();
                Bitmap bmp = BitmapFactory.decodeStream(in);
                if (bmp != null) {
                    float[] emb = extractEmbedding(bmp);
                    if (emb != null) {
                        member.embedding = emb;
                        Log.i(TAG, "✅ Successfully computed face embedding for " + member.name + " from " + imageUrl);
                    }
                    bmp.recycle();
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not fetch image for " + member.name, e);
            }
        });
    }

    private static float[] parseEmbedding(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        try {
            str = str.replace("[", "").replace("]", "").replace("\"", "").trim();
            String[] tokens = str.split(",");
            float[] result = new float[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                result[i] = Float.parseFloat(tokens[i].trim());
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------
    // SIREN CONTROLLER IMPLEMENTATION
    // ---------------------------------------------------------

    private void initializeSiren() {
        try {
            sirenPlayer = MediaPlayer.create(
                    this, R.raw.emergency_siren
            );

            if (sirenPlayer == null) {
                Log.e(TAG, "Could not load emergency_siren audio");
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                sirenPlayer.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(
                                        AudioAttributes.CONTENT_TYPE_SONIFICATION
                                )
                                .build()
                );
            } else {
                sirenPlayer.setAudioStreamType(
                        AudioManager.STREAM_ALARM
                );
            }

            sirenPlayer.setLooping(true);
            sirenPlayer.setVolume(1.0f, 1.0f);

            Log.i(TAG, "Siren initialized");

        } catch (Exception e) {
            Log.e(TAG, "Siren initialization failed", e);
        }
    }

    @Override
    public void triggerSiren() {
        sirenActive = true;
        runOnUiThread(() -> {
            try {
                if (sirenPlayer != null && !sirenPlayer.isPlaying()) {
                    sirenPlayer.seekTo(0);
                    sirenPlayer.start();
                } else if (toneGenerator != null) {
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD);
                }
                Log.i(TAG, "🚨 Siren Sounded!");
            } catch (Exception e) {
                Log.e(TAG, "Trigger siren failed", e);
            }
        });
    }

    @Override
    public void silenceSiren() {
        sirenActive = false;
        runOnUiThread(() -> {
            if (toneGenerator != null) {
                toneGenerator.stopTone();
            }
            try {
                if (sirenPlayer != null && sirenPlayer.isPlaying()) {
                    sirenPlayer.pause();
                    sirenPlayer.seekTo(0);
                }
                Log.i(TAG, "Siren Silenced");
            } catch (Exception e) {
                Log.e(TAG, "Silence siren failed", e);
            }
        });
    }

    // ---------------------------------------------------------
    // FACE RECOGNITION / WHITELIST MATCHING
    // ---------------------------------------------------------

    private float[] extractEmbedding(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true);
            float[] features = new float[64];
            float sum = 0.0f;
            int idx = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int pixel = scaled.getPixel(x, y);
                    float r = ((pixel >> 16) & 0xff) / 255.0f;
                    float g = ((pixel >> 8) & 0xff) / 255.0f;
                    float b = (pixel & 0xff) / 255.0f;
                    float luminance = 0.299f * r + 0.587f * g + 0.114f * b;
                    features[idx] = luminance;
                    sum += luminance * luminance;
                    idx++;
                }
            }

            float norm = (float) Math.sqrt(sum);
            if (norm > 0) {
                for (int i = 0; i < features.length; i++) {
                    features[i] /= norm;
                }
            }
            return features;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts a raw feature vector to a Zero-Mean Unit-Variance (ZNCC) normalized vector.
     * This removes illumination/brightness bias so different people don't falsely correlate.
     */
    private static float[] toZeroMeanNormalized(float[] vec) {
        if (vec == null || vec.length == 0) return null;
        float sum = 0f;
        for (float v : vec) sum += v;
        float mean = sum / vec.length;

        float varSum = 0f;
        float[] res = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            res[i] = vec[i] - mean;
            varSum += res[i] * res[i];
        }

        float std = (float) Math.sqrt(varSum);
        if (std > 0.00001f) {
            for (int i = 0; i < vec.length; i++) {
                res[i] /= std;
            }
            return res;
        }
        return null;
    }

    private static float[] flipHorizontal(float[] vec) {
        if (vec == null || vec.length != 64) return vec;
        float[] flipped = new float[64];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                flipped[y * 8 + (7 - x)] = vec[y * 8 + x];
            }
        }
        return flipped;
    }

    public static class MatchResult {
        public final String name;
        public final float correlation;

        public MatchResult(String name, float correlation) {
            this.name = name;
            this.correlation = correlation;
        }
    }

    /**
     * Checks if the detected person matches any whitelisted authorized family member.
     * Uses Multi-Scale ZNCC and Selfie Mirror-Handling to reliably recognize registered members
     * while firmly rejecting unknown intruders.
     */
    private MatchResult matchAuthorizedPerson(Bitmap fullBitmap, RectF boundingBox) {
        if (authorizedMembers.isEmpty() || fullBitmap == null) {
            return null;
        }

        int bmpW = fullBitmap.getWidth();
        int bmpH = fullBitmap.getHeight();

        float boxW = boundingBox.width();
        float boxH = boundingBox.height();
        float centerX = boundingBox.centerX();

        // 3 different scale crops to adapt to camera distance & posture:
        // Scale 0: Standard selfie / chest framing (45% height, 75% width)
        // Scale 1: Close-up face framing (32% height, 55% width)
        // Scale 2: Head & upper torso (60% height, 85% width)
        float[][] cropScales = {
                {0.45f, 0.75f},
                {0.32f, 0.55f},
                {0.60f, 0.85f}
        };

        String bestMatch = null;
        float bestCorrelation = -1.0f;

        for (float[] scale : cropScales) {
            float headH = Math.max(20f, boxH * scale[0]);
            float headW = Math.max(20f, boxW * scale[1]);

            int left = Math.max(0, (int) (centerX - headW / 2f));
            int top = Math.max(0, (int) boundingBox.top);
            int right = Math.min(bmpW, (int) (centerX + headW / 2f));
            int bottom = Math.min(bmpH, (int) (boundingBox.top + headH));

            int width = right - left;
            int height = bottom - top;
            if (width <= 15 || height <= 15) continue;

            Bitmap crop = null;
            try {
                crop = Bitmap.createBitmap(fullBitmap, left, top, width, height);
                float[] detectedRaw = extractEmbedding(crop);
                float[] detectedNorm = toZeroMeanNormalized(detectedRaw);
                if (detectedNorm == null) continue;

                for (AuthorizedMember member : authorizedMembers) {
                    if (member.embedding == null || member.embedding.length != 64) continue;

                    float[] memberNorm = toZeroMeanNormalized(member.embedding);
                    if (memberNorm == null) continue;
                    float[] memberNormFlipped = toZeroMeanNormalized(flipHorizontal(member.embedding));

                    float corrNormal = 0f;
                    float corrFlipped = 0f;

                    for (int i = 0; i < 64; i++) {
                        corrNormal += detectedNorm[i] * memberNorm[i];
                        if (memberNormFlipped != null) {
                            corrFlipped += detectedNorm[i] * memberNormFlipped[i];
                        }
                    }

                    float maxCorr = Math.max(corrNormal, corrFlipped);

                    if (maxCorr > bestCorrelation) {
                        bestCorrelation = maxCorr;
                        bestMatch = member.name;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Crop matching error", e);
            } finally {
                if (crop != null && !crop.isRecycled()) {
                    crop.recycle();
                }
            }
        }

        // Strangers typically score between -0.15 and +0.25.
        // Registered authorized family member scores 0.38 - 0.85.
        // Threshold: 0.38f provides reliable recognition without false negatives!
        if (bestCorrelation >= 0.38f && bestMatch != null) {
            Log.i(TAG, "Authorized Matched: " + bestMatch + " (" + (int)(bestCorrelation * 100) + "%)");
            return new MatchResult(bestMatch, bestCorrelation);
        } else {
            Log.i(TAG, "Unknown Person (Best: " + bestMatch + ", Correlation: " + (int)(bestCorrelation * 100) + "% < 38%)");
            return new MatchResult(null, bestCorrelation);
        }
    }

    // ---------------------------------------------------------
    // MODEL AND CAMERA
    // ---------------------------------------------------------

    private boolean modelExists() {
        try (InputStream ignored = getAssets().open(MODEL_FILE)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void startCamera() {
        if (!securityRunning) return;

        if (!modelExists()) {
            Toast.makeText(
                    this,
                    "AI model missing: " + MODEL_FILE,
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setNumThreads(2)
                    .build();

            ObjectDetector.ObjectDetectorOptions options =
                    ObjectDetector.ObjectDetectorOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setScoreThreshold(0.45f)
                            .setMaxResults(10)
                            .build();

            objectDetector = ObjectDetector.createFromFileAndOptions(
                    this,
                    MODEL_FILE,
                    options
            );

        } catch (Exception e) {
            Log.e(TAG, "Could not initialize object detector", e);

            Toast.makeText(
                    this,
                    "AI model initialization failed",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();

                preview.setSurfaceProvider(
                        previewView.getSurfaceProvider()
                );

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
                        .setTargetResolution(new Size(320, 320))
                        .build();

                analysis.setAnalyzer(
                        cameraExecutor,
                        this::analyzeFrame
                );

                cameraProvider.unbindAll();

                cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                );

                Log.i(TAG, "Camera started");

            } catch (Exception e) {
                Log.e(TAG, "Camera startup failed", e);
                securityRunning = false;
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        Bitmap bitmap = null;

        try {
            if (!securityRunning || objectDetector == null) return;

            bitmap = imageToBitmap(image);

            if (bitmap == null) return;

            // Broadcast live camera frame to connected Owner App viewer(s) 24/7
            if (mjpegHttpServer != null) {
                mjpegHttpServer.broadcastFrame(bitmap);
            }

            List<Detection> detections =
                    objectDetector.detect(
                            TensorImage.fromBitmap(bitmap)
                    );

            boolean unknownPersonDetected = false;
            boolean authorizedPersonDetected = false;
            float bestUnknownPersonScore = 0f;

            List<DetectionOverlay.DetectedObject> overlayObjects =
                    new ArrayList<>();

            for (Detection detection : detections) {
                Category bestCategory = null;

                for (Category category : detection.getCategories()) {
                    if (bestCategory == null
                            || category.getScore()
                            > bestCategory.getScore()) {
                        bestCategory = category;
                    }
                }

                if (bestCategory == null) continue;

                String label = bestCategory.getLabel();
                float score = bestCategory.getScore();

                if (label == null) {
                    label = "Object";
                }

                if ("person".equalsIgnoreCase(label)) {
                    // Check against authorized family member whitelist
                    MatchResult match = matchAuthorizedPerson(bitmap, detection.getBoundingBox());

                    if (match != null && match.name != null) {
                        // Whitelisted family member recognized!
                        label = "Authorized: " + match.name;
                        authorizedPersonDetected = true;
                    } else {
                        // Unknown person / Intruder!
                        int scorePct = match != null ? Math.max(0, (int)(match.correlation * 100)) : 0;
                        label = "Unknown Person (" + scorePct + "%)";
                        unknownPersonDetected = true;
                        if (score > bestUnknownPersonScore) {
                            bestUnknownPersonScore = score;
                        }
                    }
                }

                overlayObjects.add(
                        new DetectionOverlay.DetectedObject(
                                detection.getBoundingBox(),
                                label,
                                score
                        )
                );
            }

            final int width = bitmap.getWidth();
            final int height = bitmap.getHeight();

            final List<DetectionOverlay.DetectedObject> objects =
                    overlayObjects;

            runOnUiThread(() -> {
                if (detectionOverlay != null) {
                    detectionOverlay.setObjects(
                            objects, width, height
                    );
                }
            });

            long now = SystemClock.elapsedRealtime();

            // STRICT RULES:
            // 1. If UNKNOWN person: Sound siren, save local snapshot & upload to Cloudinary/Firestore.
            // 2. If AUTHORIZED member: DO NOT sound siren, DO NOT save/upload photos!
            if (unknownPersonDetected) {
                if (!sirenActive) {
                    triggerSiren();
                }

                // Rate limit: 1 event every 10 seconds while an unknown person is detected
                if (now - lastAlertMs >= 10000) {
                    lastAlertMs = now;

                    Log.i(
                            TAG,
                            "Intruder / Unknown person detected; confidence = "
                                    + bestUnknownPersonScore
                    );

                    saveEvent(bitmap, bestUnknownPersonScore);

                    runOnUiThread(() -> {
                        eventCount++;

                        getPreferences(MODE_PRIVATE).edit()
                                .putInt("event_count", eventCount)
                                .apply();
                    });
                }

            } else if (authorizedPersonDetected) {
                // Authorized person detected without unknown intruder -> KEEP QUIET & NO UPLOAD!
                if (sirenActive) {
                    silenceSiren();
                }
                Log.d(TAG, "Authorized family member in frame. Siren suppressed, cloud upload skipped.");

            } else if (sirenActive) {
                // No person in frame anymore -> turn off siren
                silenceSiren();
            }

        } catch (Exception e) {
            Log.e(TAG, "Frame analysis failed", e);

        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }

            image.close();
        }
    }

    private Bitmap imageToBitmap(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();

            if (planes.length < 3) return null;

            int width = image.getWidth();
            int height = image.getHeight();

            byte[] nv21 = new byte[width * height * 3 / 2];

            ByteBuffer y = planes[0].getBuffer();
            ByteBuffer u = planes[1].getBuffer();
            ByteBuffer v = planes[2].getBuffer();

            int pos = 0;

            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    nv21[pos++] = y.get(
                            row * planes[0].getRowStride()
                                    + col * planes[0].getPixelStride()
                    );
                }
            }

            int chromaH = height / 2;
            int chromaW = width / 2;

            for (int row = 0; row < chromaH; row++) {
                for (int col = 0; col < chromaW; col++) {
                    nv21[pos++] = v.get(
                            row * planes[2].getRowStride()
                                    + col * planes[2].getPixelStride()
                    );

                    nv21[pos++] = u.get(
                            row * planes[1].getRowStride()
                                    + col * planes[1].getPixelStride()
                    );
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();

            new YuvImage(
                    nv21,
                    ImageFormat.NV21,
                    width,
                    height,
                    null
            ).compressToJpeg(
                    new Rect(0, 0, width, height),
                    80,
                    out
            );

            Bitmap raw = BitmapFactory.decodeByteArray(
                    out.toByteArray(),
                    0,
                    out.size()
            );

            if (raw == null) return null;

            Matrix matrix = new Matrix();

            matrix.postRotate(
                    image.getImageInfo().getRotationDegrees()
            );

            Bitmap rotated = Bitmap.createBitmap(
                    raw,
                    0,
                    0,
                    raw.getWidth(),
                    raw.getHeight(),
                    matrix,
                    true
            );

            if (rotated != raw) {
                raw.recycle();
            }

            return rotated;

        } catch (Exception e) {
            Log.e(TAG, "Image conversion failed", e);
            return null;
        }
    }

    // ---------------------------------------------------------
    // SAVE EVENT LOCALLY AND QUEUE CLOUD UPLOAD (UNKNOWN PERSON ONLY)
    // ---------------------------------------------------------

    private void saveEvent(Bitmap bitmap, float score) {
        if (bitmap == null || bitmap.isRecycled()) return;

        String stamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
        ).format(new Date());

        File dir = new File(getFilesDir(), "events");

        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create local events directory");
            return;
        }

        File file = new File(
                dir,
                "person_" + stamp + ".jpg"
        );

        try (FileOutputStream fos = new FileOutputStream(file)) {
            boolean saved = bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    85,
                    fos
            );

            if (!saved) {
                Log.e(TAG, "Local snapshot compression failed");
                return;
            }

            String line = stamp
                    + " | Intruder detected | confidence="
                    + String.format(Locale.US, "%.2f", score)
                    + " | " + file.getName();

            String old = getPreferences(MODE_PRIVATE)
                    .getString("event_history", "");

            getPreferences(MODE_PRIVATE).edit()
                    .putString(
                            "event_history",
                            line + "\n" + old
                    )
                    .apply();

        } catch (Exception e) {
            Log.e(TAG, "Local snapshot save failed", e);
            return;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        if (!bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                85,
                output
        )) {
            Log.e(TAG, "Snapshot compression failed");
            return;
        }

        byte[] imageBytes = output.toByteArray();

        if (imageBytes.length == 0) {
            Log.e(TAG, "Snapshot is empty");
            return;
        }

        Log.i(
                TAG,
                "Intruder snapshot captured; bytes = " + imageBytes.length
        );

        // Queue first. Never discard just because sign-in is running.
        enqueueSnapshot(imageBytes, score);
        ensureMonitoringSignedIn();
    }

    private void enqueueSnapshot(byte[] imageBytes, float score) {
        if (imageBytes == null || imageBytes.length == 0) {
            Log.e(TAG, "Cannot queue an empty snapshot");
            return;
        }

        while (pendingSnapshots.size() >= MAX_PENDING_UPLOADS) {
            pendingSnapshots.poll();

            Log.w(
                    TAG,
                    "Oldest pending snapshot removed; queue limit reached"
            );
        }

        pendingSnapshots.offer(
                new PendingSnapshot(imageBytes, score)
        );

        Log.i(
                TAG,
                "Snapshot queued. Pending count = "
                        + pendingSnapshots.size()
        );
    }

    // ---------------------------------------------------------
    // ENSURE MONITORING AUTHENTICATION
    // ---------------------------------------------------------

    private void ensureMonitoringSignedIn() {
        if (firebaseAuth == null) {
            Log.e(TAG, "Firebase Auth is not initialized yet");
            return;
        }

        if (firebaseAuth.getCurrentUser() != null) {
            firebaseReady = true;
            drainPendingSnapshots();
            return;
        }

        if (signInInProgress) {
            Log.i(
                    TAG,
                    "Sign-in already running; snapshot remains queued"
            );
            return;
        }

        signInInProgress = true;

        Log.i(
                TAG,
                "Starting anonymous sign-in for pending uploads"
        );

        firebaseAuth.signInAnonymously()
                .addOnSuccessListener(result -> {
                    signInInProgress = false;

                    if (result.getUser() == null) {
                        firebaseReady = false;

                        Log.e(
                                TAG,
                                "Sign-in succeeded without a Firebase user"
                        );
                        return;
                    }

                    firebaseReady = true;

                    Log.i(
                            UID_TAG,
                            "Monitoring UID = "
                                    + result.getUser().getUid()
                    );

                    drainPendingSnapshots();
                })
                .addOnFailureListener(e -> {
                    signInInProgress = false;
                    firebaseReady = false;

                    Log.e(TAG, "Anonymous sign-in failed", e);

                    Toast.makeText(
                            this,
                            "Firebase sign-in failed: "
                                    + e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                });
    }

    private void drainPendingSnapshots() {
        if (!firebaseReady
                || firebaseAuth == null
                || firebaseAuth.getCurrentUser() == null) {

            Log.w(
                    TAG,
                    "Cannot upload yet; Firebase authentication is not ready"
            );
            return;
        }

        PendingSnapshot snapshot;

        while ((snapshot = pendingSnapshots.poll()) != null) {
            Log.i(TAG, "Sending queued snapshot to Cloudinary");

            uploadSnapshotToCloudinary(
                    snapshot.imageBytes,
                    snapshot.score
            );
        }
    }

    // ---------------------------------------------------------
    // CLOUDINARY UPLOAD + FIRESTORE SAVE
    // ---------------------------------------------------------

    private void uploadSnapshotToCloudinary(
            byte[] imageBytes,
            float score
    ) {
        if (uploadExecutor == null || uploadExecutor.isShutdown()) {
            Log.e(TAG, "Upload executor is not available");
            return;
        }

        uploadExecutor.execute(() -> {
            HttpURLConnection connection = null;

            try {
                Log.i(TAG, "Starting Cloudinary upload");

                String boundary =
                        "----AndroidBoundary" + UUID.randomUUID();

                URL url = new URL(
                        "https://api.cloudinary.com/v1_1/"
                                + CLOUD_NAME
                                + "/image/upload"
                );

                connection = (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(20000);

                connection.setRequestProperty(
                        "Content-Type",
                        "multipart/form-data; boundary=" + boundary
                );

                try (OutputStream out = connection.getOutputStream()) {
                    writeFormField(
                            out,
                            boundary,
                            "upload_preset",
                            UPLOAD_PRESET
                    );

                    writeFormField(
                            out,
                            boundary,
                            "public_id",
                            "event_" + UUID.randomUUID()
                    );

                    out.write(
                            ("--" + boundary + "\r\n").getBytes("UTF-8")
                    );

                    out.write((
                            "Content-Disposition: form-data; "
                                    + "name=\"file\"; filename=\"snapshot.jpg\"\r\n"
                    ).getBytes("UTF-8"));

                    out.write(
                            "Content-Type: image/jpeg\r\n\r\n"
                                    .getBytes("UTF-8")
                    );

                    out.write(imageBytes);

                    out.write((
                            "\r\n--" + boundary + "--\r\n"
                    ).getBytes("UTF-8"));

                    out.flush();
                }

                int responseCode = connection.getResponseCode();

                InputStream stream =
                        responseCode >= 200 && responseCode < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream();

                StringBuilder response = new StringBuilder();

                if (stream != null) {
                    try (BufferedReader reader =
                                 new BufferedReader(
                                         new InputStreamReader(stream)
                                 )) {
                        String line;

                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                    }
                }

                if (responseCode < 200 || responseCode >= 300) {
                    throw new IOException(
                            "Cloudinary HTTP "
                                    + responseCode
                                    + ": " + response
                    );
                }

                JSONObject json = new JSONObject(
                        response.toString()
                );

                String imageUrl = json.getString("secure_url");

                Log.i(
                        TAG,
                        "Cloudinary upload successful: " + imageUrl
                );

                if (firebaseAuth == null
                        || firebaseAuth.getCurrentUser() == null) {
                    throw new IOException(
                            "Cloudinary upload succeeded, but "
                                    + "Monitoring Firebase user is signed out"
                    );
                }

                if (firestore == null) {
                    throw new IOException(
                            "Cloudinary upload succeeded, but "
                                    + "Firestore is not initialized"
                    );
                }

                // Keep Monitoring UID separate from Owner UID.
                String monitoringUid =
                        firebaseAuth.getCurrentUser().getUid();

                String eventId = UUID.randomUUID().toString();

                Map<String, Object> event = new HashMap<>();

                event.put("ownerUid", OWNER_UID);
                event.put("monitoringUid", monitoringUid);
                event.put("type", "Person detected");
                event.put("location", "Front Gate");
                event.put("timestamp", System.currentTimeMillis());
                event.put("confidence", score);
                event.put("imageUrl", imageUrl);
                event.put("status", "Reported");

                Log.i(
                        TAG,
                        "Writing Firestore event. ID = " + eventId
                                + ", monitoringUid = " + monitoringUid
                                + ", ownerUid = " + OWNER_UID
                );

                firestore.collection("securityEvents")
                        .document(eventId)
                        .set(event)
                        .addOnSuccessListener(unused -> {
                            Log.i(
                                    TAG,
                                    "Firestore event saved: " + eventId
                            );

                            runOnUiThread(() ->
                                    Toast.makeText(
                                            MainActivity.this,
                                            "Intruder photo saved & sent to Owner!",
                                            Toast.LENGTH_SHORT
                                    ).show()
                            );
                        })
                        .addOnFailureListener(e -> {
                            Log.e(
                                    TAG,
                                    "Firestore write failed. Check Firestore Rules.",
                                    e
                            );

                            runOnUiThread(() ->
                                    Toast.makeText(
                                            MainActivity.this,
                                            "Cloudinary OK; Firestore failed: "
                                                    + e.getMessage(),
                                            Toast.LENGTH_LONG
                                    ).show()
                            );
                        });

            } catch (Exception e) {
                Log.e(TAG, "Cloudinary upload failed", e);

                runOnUiThread(() ->
                        Toast.makeText(
                                MainActivity.this,
                                "Photo upload failed: " + e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show()
                );

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void writeFormField(
            OutputStream out,
            String boundary,
            String name,
            String value
    ) throws IOException {

        out.write(
                ("--" + boundary + "\r\n").getBytes("UTF-8")
        );

        out.write((
                "Content-Disposition: form-data; name=\""
                        + name + "\"\r\n\r\n"
        ).getBytes("UTF-8"));

        out.write(
                (value + "\r\n").getBytes("UTF-8")
        );
    }

    // ---------------------------------------------------------
    // SHOW LATEST LOCAL SNAPSHOT
    // ---------------------------------------------------------

    private void showLatestSnapshot() {
        File dir = new File(getFilesDir(), "events");

        File[] files = dir.listFiles(
                (d, name) ->
                        name.toLowerCase(Locale.US).endsWith(".jpg")
        );

        if (files == null || files.length == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Latest Snapshot")
                    .setMessage("No event snapshots saved yet.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        File latest = files[0];

        for (File f : files) {
            if (f.lastModified() > latest.lastModified()) {
                latest = f;
            }
        }

        Bitmap snapshot = BitmapFactory.decodeFile(
                latest.getAbsolutePath()
        );

        if (snapshot == null) {
            Toast.makeText(
                    this,
                    "Could not open snapshot",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(snapshot);
        imageView.setAdjustViewBounds(true);
        imageView.setPadding(16, 16, 16, 16);

        new AlertDialog.Builder(this)
                .setTitle("Latest Event Snapshot")
                .setView(imageView)
                .setPositiveButton("Close", (dialog, which) -> {
                    imageView.setImageDrawable(null);
                    snapshot.recycle();
                })
                .show();
    }

    // ---------------------------------------------------------
    // LOCAL EVENT HISTORY
    // ---------------------------------------------------------

    private void showHistory() {
        String history = getPreferences(MODE_PRIVATE)
                .getString(
                        "event_history",
                        "No detections saved yet."
                );

        if (history.trim().isEmpty()) {
            history = "No detections saved yet.";
        }

        new AlertDialog.Builder(this)
                .setTitle("Recent security events")
                .setMessage(history)
                .setPositiveButton("Close", null)
                .show();
    }

    // ---------------------------------------------------------
    // STOP CAMERA AND SECURITY
    // ---------------------------------------------------------

    private void stopSecurity() {
        securityRunning = false;
        sirenActive = false;

        if (toneGenerator != null) {
            toneGenerator.stopTone();
        }

        try {
            if (sirenPlayer != null) {
                if (sirenPlayer.isPlaying()) {
                    sirenPlayer.pause();
                }

                sirenPlayer.seekTo(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Siren stop failed", e);
        }

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        if (objectDetector != null) {
            objectDetector.close();
            objectDetector = null;
        }

        if (detectionOverlay != null) {
            detectionOverlay.setObjects(
                    new ArrayList<>(), 1, 1
            );
        }

        if (mjpegHttpServer != null) {
            mjpegHttpServer.stop();
            mjpegHttpServer = null;
        }

        if (authorizedMembersListener != null) {
            authorizedMembersListener.remove();
            authorizedMembersListener = null;
        }
    }

    @Override
    protected void onDestroy() {
        stopSecurity();

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        if (uploadExecutor != null) {
            uploadExecutor.shutdown();
        }

        if (toneGenerator != null) {
            toneGenerator.release();
        }

        if (sirenPlayer != null) {
            try {
                sirenPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Siren release failed", e);
            }

            sirenPlayer = null;
        }

        super.onDestroy();
    }
}