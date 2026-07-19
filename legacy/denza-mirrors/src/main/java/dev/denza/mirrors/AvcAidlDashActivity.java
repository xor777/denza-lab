package dev.denza.mirrors;

import android.app.Activity;
import android.app.Presentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AvcAidlDashActivity extends Activity {
    private static final String TAG = "DenzaProjectionProbe";
    private static final String CLUSTER_DISPLAY_NAME = "shared_fission_bg_XDJAScreenProjection_0";
    private static final int DEFAULT_DISPLAY_ID = 3;
    private static final int DEFAULT_VIEWPOINT = 2002; // SUB_CAMERA_LEFT
    private static final long DEFAULT_DURATION_MS = 20000L;
    private static final String DEFAULT_SLOT = "full";
    private static final int DEFAULT_CENTER_EXTEND_PERCENT = 20;
    private static final float EDGE_SHADE_HEIGHT_RATIO = 0.20f;
    private static final int EDGE_SHADE_ALPHA = 179;
    private static final int DEFAULT_IMAGE_ENHANCEMENT_STRENGTH = 100;
    private static final float COLOR_CONTRAST_SCALE = 1.62f;
    private static final float COLOR_BRIGHTNESS_OFFSET = 28.0f;
    private static final float COLOR_SATURATION = 0.80f;
    private static final String IMAGE_ENHANCEMENT_NORMAL = "normal";
    private static final String IMAGE_ENHANCEMENT_CONTRAST = "contrast";
    private static final String STATUS_FILE_NAME = "avc_aidl_status.txt";
    public static final String EXTRA_FINISH = "finish";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static AvcAidlDashActivity activeInstance;
    private AvcPresentation presentation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra(EXTRA_FINISH, false)) {
            finishActiveInstance();
            finishAndRemoveTask();
            return;
        }
        activeInstance = this;
        configureHostWindow();
        showFromIntent(getIntent());
    }

    private void showFromIntent(Intent intent) {
        int requestedDisplayId = intent.getIntExtra("display_id", DEFAULT_DISPLAY_ID);
        int viewpoint = intent.getIntExtra("viewpoint", DEFAULT_VIEWPOINT);
        boolean uTurnEnabled = intent.getBooleanExtra("uturn", false);
        String slot = normalizeSlot(intent.getStringExtra("slot"));
        String cropSource = normalizeCropSource(intent.getStringExtra("crop_source"));
        String imageEnhancement = normalizeImageEnhancement(intent.getStringExtra("image_enhancement"));
        int imageEnhancementStrength = Math.max(0, Math.min(100,
                intent.getIntExtra("image_enhancement_strength",
                        DEFAULT_IMAGE_ENHANCEMENT_STRENGTH)));
        if (!IMAGE_ENHANCEMENT_CONTRAST.equals(imageEnhancement)) {
            imageEnhancementStrength = 0;
        }
        int centerExtendPercent = Math.max(0, Math.min(100,
                intent.getIntExtra("center_extend_percent", DEFAULT_CENTER_EXTEND_PERCENT)));
        boolean overlayWindow = intent.getBooleanExtra("overlay_window", true);
        boolean diagnosticPreview = intent.getBooleanExtra("diagnostic_preview", false);
        boolean diagnosticVisible = intent.getBooleanExtra("diagnostic_visible", false);
        String previewMode = intent.getStringExtra("preview_mode");
        long durationMs = Math.max(1000L, intent.getLongExtra("duration_ms", DEFAULT_DURATION_MS));
        Display display = findClusterDisplay(this, requestedDisplayId);
        if (display == null) {
            Log.i(TAG, "avc aidl display not found id=" + requestedDisplayId);
            writeStatus(this, "diagnostic preview display not found id=" + requestedDisplayId);
            finishAndRemoveTask();
            return;
        }

        try {
            handler.removeCallbacksAndMessages(null);
            if (presentation != null) {
                presentation.dismiss();
                presentation = null;
            }
            presentation = showPresentation(display, viewpoint, uTurnEnabled, slot,
                    cropSource, imageEnhancement, imageEnhancementStrength,
                    centerExtendPercent, overlayWindow, diagnosticPreview,
                    diagnosticVisible, previewMode);
            Log.i(TAG, "avc aidl presentation shown displayId=" + display.getDisplayId()
                    + " name=" + display.getName()
                    + " viewpoint=" + viewpoint
                    + " slot=" + slot
                    + " cropSource=" + cropSource
                    + " imageEnhancement=" + imageEnhancement
                    + " imageEnhancementStrength=" + imageEnhancementStrength
                    + " centerExtendPercent=" + centerExtendPercent
                    + " overlayWindow=" + overlayWindow
                    + " diagnosticPreview=" + diagnosticPreview
                    + " diagnosticVisible=" + diagnosticVisible
                    + " previewMode=" + previewMode
                    + " uturn=" + uTurnEnabled);
        } catch (RuntimeException e) {
            Log.i(TAG, "avc aidl presentation show failed", e);
            writeStatus(this, (diagnosticPreview ? "diagnostic preview" : "avc presentation")
                    + " failed: " + shortError(e));
            finishAndRemoveTask();
            return;
        }

        handler.postDelayed(this::finishAndRemoveTask, durationMs);
    }

    private AvcPresentation showPresentation(Display display, int viewpoint,
            boolean uTurnEnabled, String slot, String cropSource,
            String imageEnhancement, int imageEnhancementStrength,
            int centerExtendPercent, boolean overlayWindow, boolean diagnosticPreview,
            boolean diagnosticVisible, String previewMode) {
        AvcPresentation first = new AvcPresentation(this, display, viewpoint, uTurnEnabled,
                slot, cropSource, imageEnhancement, imageEnhancementStrength,
                centerExtendPercent, overlayWindow, diagnosticPreview,
                diagnosticVisible, previewMode);
        try {
            first.show();
            return first;
        } catch (RuntimeException e) {
            if (!overlayWindow) {
                throw e;
            }
            Log.i(TAG, "overlay presentation failed, retrying normal window", e);
            AvcPresentation fallback = new AvcPresentation(this, display, viewpoint,
                    uTurnEnabled, slot, cropSource, imageEnhancement,
                    imageEnhancementStrength, centerExtendPercent, false,
                    diagnosticPreview, diagnosticVisible, previewMode);
            fallback.show();
            return fallback;
        }
    }

    private void configureHostWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = 1;
        params.height = 1;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = -1000;
        params.y = -1000;
        params.alpha = 0.0f;
        window.setAttributes(params);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_FINISH, false)) {
            finishFast();
            return;
        }
        setIntent(intent);
        showFromIntent(intent);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        if (activeInstance == this) {
            activeInstance = null;
        }
        super.onDestroy();
    }

    public static boolean finishActiveInstance() {
        AvcAidlDashActivity activity = activeInstance;
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::finishFast);
        return true;
    }

    static boolean finishActiveInstanceSync(long timeoutMs) {
        AvcAidlDashActivity activity = activeInstance;
        if (activity == null) {
            return false;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            activity.finishFast();
            return true;
        }

        CountDownLatch latch = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            try {
                activity.finishFast();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    private void finishFast() {
        handler.removeCallbacksAndMessages(null);
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        finishAndRemoveTask();
    }

    static Display findClusterDisplay(Context context, int requestedDisplayId) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            return null;
        }
        Display display = displayManager.getDisplay(requestedDisplayId);
        if (display != null) {
            return display;
        }
        for (Display candidate : displayManager.getDisplays()) {
            if (candidate.getName() != null && candidate.getName().contains(CLUSTER_DISPLAY_NAME)) {
                return candidate;
            }
        }
        return null;
    }

    private static String normalizeSlot(String slot) {
        if ("left".equals(slot) || "right".equals(slot) || "center".equals(slot)) {
            return slot;
        }
        return DEFAULT_SLOT;
    }

    private static String normalizeCropSource(String cropSource) {
        if ("left".equals(cropSource) || "right".equals(cropSource)) {
            return cropSource;
        }
        return "none";
    }

    private static String normalizeImageEnhancement(String imageEnhancement) {
        return IMAGE_ENHANCEMENT_CONTRAST.equals(imageEnhancement)
                ? IMAGE_ENHANCEMENT_CONTRAST
                : IMAGE_ENHANCEMENT_NORMAL;
    }

    private static String shortError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + " " + message;
    }

    static String readStatus(Context context) {
        File statusFile = new File(context.getFilesDir(), STATUS_FILE_NAME);
        if (!statusFile.exists()) {
            return "";
        }
        try (FileInputStream input = new FileInputStream(statusFile)) {
            byte[] buffer = new byte[(int) Math.min(statusFile.length(), 4096)];
            int read = input.read(buffer);
            if (read <= 0) {
                return "";
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    static void writeStatus(Context context, String status) {
        File statusFile = new File(context.getFilesDir(), STATUS_FILE_NAME);
        try (FileOutputStream output = new FileOutputStream(statusFile, false)) {
            output.write(status.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
        } catch (IOException e) {
            Log.i(TAG, "status file write failed", e);
        }
    }

    static final class AvcPresentation extends Presentation
            implements SurfaceHolder.Callback, TextureView.SurfaceTextureListener {
        private final int viewpoint;
        private final boolean uTurnEnabled;
        private final String slot;
        private final String cropSource;
        private final String imageEnhancement;
        private final int imageEnhancementStrength;
        private final int centerExtendPercent;
        private final boolean overlayWindow;
        private final boolean diagnosticPreview;
        private final boolean diagnosticVisible;
        private final String previewMode;
        private SurfaceView surfaceView;
        private TextureView textureView;
        private AvcAidlClient avcClient;
        private boolean bound;
        private boolean initAttempted;
        private Surface surface;
        private boolean ownsSurface;
        private boolean dismissed;

        private final ServiceConnection serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                avcClient = new AvcAidlClient(service);
                bound = true;
                runAvcInitIfReady();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                bound = false;
                avcClient = null;
                setStatus("avc aidl disconnected");
            }
        };

        AvcPresentation(Context outerContext, Display display, int viewpoint,
                boolean uTurnEnabled, String slot, String cropSource,
                String imageEnhancement, int imageEnhancementStrength,
                int centerExtendPercent, boolean overlayWindow, boolean diagnosticPreview,
                boolean diagnosticVisible, String previewMode) {
            super(outerContext, display);
            this.viewpoint = viewpoint;
            this.uTurnEnabled = uTurnEnabled;
            this.slot = slot;
            this.cropSource = cropSource;
            this.imageEnhancement = imageEnhancement;
            this.imageEnhancementStrength = imageEnhancementStrength;
            this.centerExtendPercent = centerExtendPercent;
            this.overlayWindow = overlayWindow;
            this.diagnosticPreview = diagnosticPreview;
            this.diagnosticVisible = diagnosticVisible;
            this.previewMode = previewMode;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            configurePresentationWindow();
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.TRANSPARENT);

            if (diagnosticPreview) {
                if (diagnosticVisible) {
                    addDiagnosticPreview(root);
                } else {
                    addInvisibleDiagnosticPreview(root);
                }
                setContentView(root);
                setStatus("diagnostic preview shown mode=" + normalizedPreviewMode()
                        + " visible=" + diagnosticVisible);
                return;
            }

            FrameLayout surfaceFrame = new FrameLayout(getContext());
            surfaceFrame.setBackgroundColor(Color.BLACK);
            root.addView(surfaceFrame, buildSurfaceFrameParams());

            textureView = new TextureView(getContext());
            textureView.setOpaque(true);
            applyImageEnhancement(textureView);
            textureView.setSurfaceTextureListener(this);
            surfaceFrame.addView(textureView, buildSurfaceViewParams(surfaceFrame));
            surfaceFrame.addView(new EdgeShadeView(getContext()), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            setContentView(root);
            bindAvcService();
        }

        private void addDiagnosticPreview(FrameLayout root) {
            String mode = normalizedPreviewMode();
            if (SideCameraOverlayMonitorService.CAMERA_POSITION_SIDES.equals(mode)) {
                root.addView(new DiagnosticPreviewView(getContext(), "LEFT"),
                        buildFrameParamsForSlot("left"));
                root.addView(new DiagnosticPreviewView(getContext(), "RIGHT"),
                        buildFrameParamsForSlot("right"));
                return;
            }
            root.addView(new DiagnosticPreviewView(getContext(), "CENTER"),
                    buildFrameParamsForSlot("center"));
        }

        private void addInvisibleDiagnosticPreview(FrameLayout root) {
            root.addView(new InvisibleDiagnosticPreviewView(getContext()),
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    ));
        }

        private String normalizedPreviewMode() {
            return SideCameraOverlayMonitorService.CAMERA_POSITION_CENTER.equals(previewMode)
                    ? SideCameraOverlayMonitorService.CAMERA_POSITION_CENTER
                    : SideCameraOverlayMonitorService.CAMERA_POSITION_SIDES;
        }

        private void configurePresentationWindow() {
            Window window = getWindow();
            if (window == null) {
                return;
            }
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            if (overlayWindow) {
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            }
        }

        @Override
        public void dismiss() {
            if (dismissed) {
                return;
            }
            dismissed = true;
            try {
                super.dismiss();
            } finally {
                releaseAvc();
            }
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            setSurface(holder.getSurface(), false);
            runAvcInitIfReady();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            setSurface(holder.getSurface(), false);
            if (!initAttempted) {
                setStatus("surface " + width + "x" + height + " vp=" + viewpoint);
            }
            runAvcInitIfReady();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            clearSurface();
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            surfaceTexture.setDefaultBufferSize(width, height);
            setSurface(new Surface(surfaceTexture), true);
            if (!initAttempted) {
                setStatus("texture surface " + width + "x" + height
                        + " vp=" + viewpoint + " crop=" + cropSource);
            }
            runAvcInitIfReady();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                int width, int height) {
            surfaceTexture.setDefaultBufferSize(width, height);
            if (!initAttempted) {
                setStatus("texture changed " + width + "x" + height
                        + " vp=" + viewpoint + " crop=" + cropSource);
            }
            runAvcInitIfReady();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            clearSurface();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }

        private FrameLayout.LayoutParams buildSurfaceFrameParams() {
            return buildFrameParamsForSlot(slot);
        }

        private FrameLayout.LayoutParams buildFrameParamsForSlot(String frameSlot) {
            if ("full".equals(frameSlot)) {
                return new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                );
            }

            DisplayMetrics metrics = new DisplayMetrics();
            getDisplay().getRealMetrics(metrics);
            int slotWidth = Math.max(1, metrics.widthPixels / 3);
            int frameWidth = extendedFrameWidth(slotWidth);
            int gravity = Gravity.START;
            if ("right".equals(frameSlot)) {
                gravity = Gravity.END;
            } else if ("center".equals(frameSlot)) {
                gravity = Gravity.CENTER_HORIZONTAL;
                frameWidth = extendedFrameWidth(slotWidth);
            }
            return new FrameLayout.LayoutParams(
                    frameWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    gravity | Gravity.TOP
            );
        }

        private FrameLayout.LayoutParams buildSurfaceViewParams(FrameLayout surfaceFrame) {
            surfaceFrame.setClipChildren(true);
            surfaceFrame.setClipToPadding(true);
            if ("none".equals(cropSource)) {
                return new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                );
            }

            DisplayMetrics metrics = new DisplayMetrics();
            getDisplay().getRealMetrics(metrics);
            int slotWidth = Math.max(1, metrics.widthPixels / 3);
            int surfaceWidth = extendedFrameWidth(slotWidth) * 2;
            int gravity = "right".equals(cropSource) ? Gravity.END : Gravity.START;
            return new FrameLayout.LayoutParams(
                    surfaceWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    gravity | Gravity.TOP
            );
        }

        private int extendedFrameWidth(int slotWidth) {
            return slotWidth + Math.round(slotWidth * (centerExtendPercent / 100.0f));
        }

        private void applyImageEnhancement(TextureView view) {
            float amount = Math.max(0f, Math.min(1f, imageEnhancementStrength / 100.0f));
            if (!IMAGE_ENHANCEMENT_CONTRAST.equals(imageEnhancement) || amount <= 0f) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    view.setRenderEffect(null);
                }
                view.setLayerType(View.LAYER_TYPE_NONE, null);
                return;
            }

            ColorMatrixColorFilter colorFilter =
                    new ColorMatrixColorFilter(enhancementMatrix(amount));
            Paint layerPaint = new Paint();
            layerPaint.setColorFilter(colorFilter);
            view.setLayerType(View.LAYER_TYPE_HARDWARE, layerPaint);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.setRenderEffect(RenderEffect.createColorFilterEffect(colorFilter));
            }
            Log.i(TAG, "image enhancement applied mode=" + imageEnhancement
                    + " strength=" + imageEnhancementStrength
                    + " sdk=" + Build.VERSION.SDK_INT);
        }

        private static ColorMatrix enhancementMatrix(float amount) {
            float contrast = lerp(1.0f, COLOR_CONTRAST_SCALE, amount);
            float brightness = COLOR_BRIGHTNESS_OFFSET * amount;
            float saturation = lerp(1.0f, COLOR_SATURATION, amount);
            float offset = (-0.5f * contrast + 0.5f) * 255f + brightness;
            ColorMatrix contrastMatrix = new ColorMatrix(new float[] {
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
            });
            ColorMatrix saturationMatrix = new ColorMatrix();
            saturationMatrix.setSaturation(saturation);
            saturationMatrix.postConcat(contrastMatrix);
            return saturationMatrix;
        }

        private static float lerp(float from, float to, float amount) {
            return from + (to - from) * amount;
        }

        private static final class DiagnosticPreviewView extends View {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final String label;

            DiagnosticPreviewView(Context context, String label) {
                super(context);
                this.label = label;
                setWillNotDraw(false);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int width = getWidth();
                int height = getHeight();
                if (width <= 0 || height <= 0) {
                    return;
                }

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(10, 24, 28));
                canvas.drawRect(0, 0, width, height, paint);

                paint.setColor(Color.rgb(20, 156, 132));
                float inset = Math.max(8f, width * 0.04f);
                canvas.drawRect(inset, inset, width - inset, height - inset, paint);

                paint.setColor(Color.rgb(6, 16, 20));
                float inner = inset * 2.0f;
                canvas.drawRect(inner, inner, width - inner, height - inner, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(3f, width * 0.008f));
                paint.setColor(Color.rgb(214, 255, 246));
                canvas.drawRect(inner, inner, width - inner, height - inner, paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                paint.setTextSize(Math.max(34f, height * 0.10f));
                paint.setColor(Color.WHITE);
                canvas.drawText(label, width / 2f, height * 0.47f, paint);

                paint.setTypeface(android.graphics.Typeface.DEFAULT);
                paint.setTextSize(Math.max(18f, height * 0.046f));
                paint.setColor(Color.rgb(190, 226, 229));
                canvas.drawText("Denza Mirrors check", width / 2f, height * 0.56f, paint);
            }
        }

        private static final class InvisibleDiagnosticPreviewView extends View {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            InvisibleDiagnosticPreviewView(Context context) {
                super(context);
                setWillNotDraw(false);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.TRANSPARENT);
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            }
        }

        private static final class EdgeShadeView extends View {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private LinearGradient topGradient;
            private LinearGradient bottomGradient;
            private int shaderWidth;
            private int shaderHeight;
            private int shaderFadeHeight;

            EdgeShadeView(Context context) {
                super(context);
                setWillNotDraw(false);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int width = getWidth();
                int height = getHeight();
                if (width <= 0 || height <= 0) {
                    return;
                }

                int fadeHeight = Math.max(1, Math.round(height * EDGE_SHADE_HEIGHT_RATIO));
                ensureGradients(width, height, fadeHeight);

                paint.setShader(topGradient);
                canvas.drawRect(0, 0, width, fadeHeight, paint);
                paint.setShader(bottomGradient);
                canvas.drawRect(0, height - fadeHeight, width, height, paint);
                paint.setShader(null);
            }

            private void ensureGradients(int width, int height, int fadeHeight) {
                if (topGradient != null
                        && shaderWidth == width
                        && shaderHeight == height
                        && shaderFadeHeight == fadeHeight) {
                    return;
                }

                int darkest = Color.argb(EDGE_SHADE_ALPHA, 0, 0, 0);
                int transparent = Color.argb(0, 0, 0, 0);
                topGradient = new LinearGradient(0, 0, 0, fadeHeight,
                        darkest, transparent, Shader.TileMode.CLAMP);
                bottomGradient = new LinearGradient(0, height - fadeHeight, 0, height,
                        transparent, darkest, Shader.TileMode.CLAMP);
                shaderWidth = width;
                shaderHeight = height;
                shaderFadeHeight = fadeHeight;
            }
        }

        private void bindAvcService() {
            Intent intent = new Intent("com.byd.avc.aidl.service");
            intent.setPackage("com.byd.avc");
            boolean bindStarted = getContext().bindService(intent, serviceConnection,
                    Context.BIND_AUTO_CREATE);
            setStatus(bindStarted
                    ? "avc aidl binding vp=" + viewpoint
                    : "avc aidl bind failed");
            Log.i(TAG, "avc aidl bindService result=" + bindStarted);
        }

        private void runAvcInitIfReady() {
            if (!bound || avcClient == null || surface == null || !surface.isValid()) {
                return;
            }
            if (initAttempted) {
                return;
            }
            initAttempted = true;
            String step = "getName";
            try {
                setStatus("avc step " + step);
                String name = avcClient.getName();
                step = "bufferType";
                setStatus("avc step " + step);
                int bufferType = avcClient.getSupportPushBufferType();
                if (uTurnEnabled) {
                    step = "setuTurn(true)";
                    setStatus("avc step " + step);
                    avcClient.setuTurnEnable(true);
                }
                step = "initDisplay";
                setStatus("avc step " + step);
                boolean initialized = avcClient.initDisplay(surface);
                step = "setViewpoint " + viewpoint;
                setStatus("avc step " + step);
                avcClient.setViewpoint(viewpoint);
                setStatus("avc " + name + " init=" + initialized
                        + " buffer=" + bufferType + " vp=" + viewpoint);
                Log.i(TAG, "avc aidl init name=" + name
                        + " initialized=" + initialized
                        + " bufferType=" + bufferType
                        + " viewpoint=" + viewpoint
                        + " uturn=" + uTurnEnabled);
            } catch (RemoteException | RuntimeException e) {
                setStatus("avc " + step + " failed: " + shortError(e));
                Log.i(TAG, "avc aidl init failed", e);
            }
        }

        private void releaseAvc() {
            if (avcClient != null) {
                try {
                    avcClient.freeDisplay();
                    if (uTurnEnabled) {
                        avcClient.setuTurnEnable(false);
                    }
                } catch (RemoteException | RuntimeException e) {
                    Log.i(TAG, "avc aidl release failed", e);
                }
            }
            if (bound) {
                try {
                    getContext().unbindService(serviceConnection);
                } catch (IllegalArgumentException e) {
                    Log.i(TAG, "avc aidl unbind skipped", e);
                }
            }
            bound = false;
            avcClient = null;
            clearSurface();
        }

        private void setSurface(Surface nextSurface, boolean owned) {
            if (surface != null && ownsSurface && surface != nextSurface) {
                surface.release();
            }
            surface = nextSurface;
            ownsSurface = owned;
        }

        private void clearSurface() {
            if (surface != null && ownsSurface) {
                surface.release();
            }
            surface = null;
            ownsSurface = false;
        }

        private void setStatus(String status) {
            writeStatusFile(status);
            System.err.println(TAG + ": " + status);
            Log.i(TAG, status);
        }

        private void writeStatusFile(String status) {
            AvcAidlDashActivity.writeStatus(getContext(), status);
        }
    }

    private static final class AvcAidlClient {
        private static final String DESCRIPTOR = "com.byd.avc.aidl.IAVCAidlInterface";
        private static final int TRANSACTION_GET_NAME = 1;
        private static final int TRANSACTION_SET_VIEWPOINT = 5;
        private static final int TRANSACTION_GET_SUPPORT_PUSH_BUFFER_TYPE = 6;
        private static final int TRANSACTION_INIT_DISPLAY = 7;
        private static final int TRANSACTION_FREE_DISPLAY = 9;
        private static final int TRANSACTION_SET_UTURN_ENABLE = 10;

        private final IBinder remote;

        AvcAidlClient(IBinder remote) {
            this.remote = remote;
        }

        String getName() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(TRANSACTION_GET_NAME, data, reply, 0);
                reply.readException();
                return reply.readString();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        int getSupportPushBufferType() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(TRANSACTION_GET_SUPPORT_PUSH_BUFFER_TYPE, data, reply, 0);
                reply.readException();
                return reply.readInt();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        boolean initDisplay(Surface surface) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                if (surface == null) {
                    data.writeInt(0);
                } else {
                    data.writeInt(1);
                    surface.writeToParcel(data, 0);
                }
                remote.transact(TRANSACTION_INIT_DISPLAY, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        void setViewpoint(int viewpoint) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(viewpoint);
                remote.transact(TRANSACTION_SET_VIEWPOINT, data, reply, 0);
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        void setuTurnEnable(boolean enabled) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(enabled ? 1 : 0);
                remote.transact(TRANSACTION_SET_UTURN_ENABLE, data, reply, 0);
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        void freeDisplay() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(TRANSACTION_FREE_DISPLAY, data, reply, 0);
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }
}
