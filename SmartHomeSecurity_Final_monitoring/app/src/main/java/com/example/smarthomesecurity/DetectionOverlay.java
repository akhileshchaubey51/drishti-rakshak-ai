package com.example.smarthomesecurity;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Draws model-provided bounding boxes and class labels over the live camera preview. */
public class DetectionOverlay extends View {
    public static class DetectedObject {
        public final RectF bounds;
        public final String label;
        public final float confidence;
        public DetectedObject(RectF bounds, String label, float confidence) {
            this.bounds = new RectF(bounds);
            this.label = label == null || label.trim().isEmpty() ? "Object" : label;
            this.confidence = confidence;
        }
    }

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<DetectedObject> objects = new ArrayList<>();
    private int sourceWidth = 1;
    private int sourceHeight = 1;

    public DetectionOverlay(Context context) { super(context); init(); }
    public DetectionOverlay(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public DetectionOverlay(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        setWillNotDraw(false);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);
        boxPaint.setColor(Color.rgb(75, 230, 135));
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(34f);
        labelPaint.setFakeBoldText(true);
        labelBackgroundPaint.setStyle(Paint.Style.FILL);
        labelBackgroundPaint.setColor(Color.argb(220, 23, 60, 46));
    }

    public void setObjects(List<DetectedObject> newObjects, int imageWidth, int imageHeight) {
        objects = new ArrayList<>(newObjects);
        sourceWidth = Math.max(1, imageWidth);
        sourceHeight = Math.max(1, imageHeight);
        postInvalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (objects.isEmpty() || getWidth() == 0 || getHeight() == 0) return;
        // Camera frames and preview may have different aspect ratios; scale X/Y separately
        // so every detection remains visible. If the preview crops, boxes can be approximate.
        float sx = getWidth() / (float) sourceWidth;
        float sy = getHeight() / (float) sourceHeight;
        for (DetectedObject obj : objects) {
            RectF r = new RectF(obj.bounds.left * sx, obj.bounds.top * sy,
                    obj.bounds.right * sx, obj.bounds.bottom * sy);
            r.left = Math.max(0, Math.min(getWidth(), r.left));
            r.right = Math.max(0, Math.min(getWidth(), r.right));
            r.top = Math.max(0, Math.min(getHeight(), r.top));
            r.bottom = Math.max(0, Math.min(getHeight(), r.bottom));
            if (r.width() < 2 || r.height() < 2) continue;
            boolean isAuthorized = obj.label != null && obj.label.toLowerCase().contains("authorized");
            boolean isPerson = "person".equalsIgnoreCase(obj.label) || (obj.label != null && obj.label.toLowerCase().contains("unknown"));

            if (isAuthorized) {
                boxPaint.setColor(Color.rgb(0, 255, 136));
                labelBackgroundPaint.setColor(Color.argb(230, 10, 80, 40));
            } else if (isPerson) {
                boxPaint.setColor(Color.RED);
                labelBackgroundPaint.setColor(Color.rgb(190, 20, 20));
            } else {
                boxPaint.setColor(Color.rgb(75, 230, 135));
                labelBackgroundPaint.setColor(Color.argb(220, 23, 60, 46));
            }

            canvas.drawRect(r, boxPaint);
            String label = String.format(Locale.getDefault(), "%s %.0f%%", obj.label, obj.confidence * 100f);
            float textWidth = labelPaint.measureText(label);
            float labelHeight = labelPaint.getTextSize() + 16f;
            float labelTop = Math.max(0, r.top - labelHeight);
            float labelRight = Math.min(getWidth(), r.left + textWidth + 20f);
            canvas.drawRect(r.left, labelTop, labelRight, labelTop + labelHeight, labelBackgroundPaint);
            canvas.drawText(label, r.left + 10f, labelTop + labelPaint.getTextSize() + 2f, labelPaint);
        }
    }
}
