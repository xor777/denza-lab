package dev.denza.nightvision.probe;

import android.app.Activity;
import android.app.Presentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
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
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Short-lived, host-driven evaluation of front-facing camera sources on the
 * instrument display. The package keeps its historical experiment name; a
 * successful render is not evidence that the source is useful at distance.
 *
 * This probe defaults to SUB_CAMERA_FRONT (2001) and permits only the known
 * Denza Mirrors left-PIP channel (3205) as a diagnostic control. It has no
 * launcher entry, never starts at boot, and automatically releases the vendor
 * display after at most ten seconds.
 */
public final class NightVisionProbeActivity extends Activity {
    private static final String TAG = "NightVisionProbe";
    private static final String CAMERA_OVERLAY_DISPLAY_NAME =
            "shared_fission_bg_XDJAScreenProjection_1";
    private static final String CAMERA_BASE_DISPLAY_NAME =
            "shared_fission_bg_XDJAScreenProjection_0";
    private static final int FRONT_VIEWPOINT = 2001;
    private static final int SMALL_FRONT_VIEWPOINT = 2009;
    private static final int MIRRORS_LEFT_VIEWPOINT = 3205;
    private static final long DEFAULT_DURATION_MS = 8_000L;
    private static final long MAX_DURATION_MS = 10_000L;
    private static final String EXTRA_DURATION_MS = "duration_ms";
    private static final String EXTRA_FINISH = "finish";
    private static final String EXTRA_RUN_ID = "run_id";
    private static final String EXTRA_STOCK_PROJECTION = "stock_projection";
    private static final String EXTRA_SURFACE_VIEW = "surface_view";
    private static final String EXTRA_VIEWPOINT = "viewpoint";
    private static final String EXTRA_PROCESSING = "processing";
    private static final String EXTRA_CROP_SOURCE = "crop_source";
    private static final String EXTRA_CROP_WIDTH_PERCENT = "crop_width_percent";
    private static final String EXTRA_WINDOW_MODE = "window_mode";
    private static final String EXTRA_DISPLAY_LAYER = "display_layer";
    private static final String PROCESSING_RAW = "raw";
    private static final String PROCESSING_NIGHT = "night";
    private static final String PROCESSING_NIGHT_ZEBRA = "night_zebra";
    private static final String CROP_NONE = "none";
    private static final String CROP_LEFT = "left";
    private static final String CROP_RIGHT = "right";
    private static final String WINDOW_FULL = "full";
    private static final String WINDOW_CENTER = "center";
    private static final String DISPLAY_OVERLAY = "overlay";
    private static final String DISPLAY_BASE = "base";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread vendorTeardownThread;
    private Handler vendorTeardownHandler;
    private ProbePresentation presentation;
    private StockProjectionSession stockProjectionSession;
    private String runId = "manual";
    private boolean stopping;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureHostWindow();
        startVendorThread();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent.getBooleanExtra(EXTRA_FINISH, false)) {
            stopAndFinish("explicit stop");
            return;
        }
        if (presentation != null || stockProjectionSession != null || stopping) {
            report("refused duplicate start");
            return;
        }

        runId = sanitizeRunId(intent.getStringExtra(EXTRA_RUN_ID));
        long durationMs = Math.max(
                1_000L,
                Math.min(MAX_DURATION_MS, intent.getLongExtra(
                        EXTRA_DURATION_MS,
                        DEFAULT_DURATION_MS)));
        int viewpoint = intent.getIntExtra(EXTRA_VIEWPOINT, FRONT_VIEWPOINT);
        boolean surfaceView = intent.getBooleanExtra(EXTRA_SURFACE_VIEW, false);
        String processing = normalizeProcessing(intent.getStringExtra(EXTRA_PROCESSING));
        String cropSource = normalizeCropSource(intent.getStringExtra(EXTRA_CROP_SOURCE));
        String windowMode = normalizeWindowMode(intent.getStringExtra(EXTRA_WINDOW_MODE));
        String displayLayer = normalizeDisplayLayer(
                intent.getStringExtra(EXTRA_DISPLAY_LAYER));
        int cropWidthPercent = Math.max(
                30,
                Math.min(100, intent.getIntExtra(EXTRA_CROP_WIDTH_PERCENT, 50)));
        if (viewpoint != FRONT_VIEWPOINT
                && viewpoint != SMALL_FRONT_VIEWPOINT
                && viewpoint != MIRRORS_LEFT_VIEWPOINT) {
            report("failed unsupported viewpoint=" + viewpoint);
            stopAndFinish("unsupported viewpoint");
            return;
        }
        if (processing == null) {
            report("failed unsupported processing="
                    + intent.getStringExtra(EXTRA_PROCESSING));
            stopAndFinish("unsupported processing");
            return;
        }
        if (surfaceView && !PROCESSING_RAW.equals(processing)) {
            report("failed processing requires TextureView mode=" + processing);
            stopAndFinish("processing surface mismatch");
            return;
        }
        if (surfaceView && !CROP_NONE.equals(cropSource)) {
            report("failed crop requires TextureView source=" + cropSource);
            stopAndFinish("crop surface mismatch");
            return;
        }
        if (intent.getBooleanExtra(EXTRA_STOCK_PROJECTION, false)) {
            stockProjectionSession = new StockProjectionSession(
                    this,
                    new StockProjectionSession.Listener() {
                        @Override
                        public void onReady(String details) {
                            report("ready " + details);
                        }

                        @Override
                        public void onFailure(String details) {
                            report("failed " + details);
                            stopAndFinish("stock projection failure");
                        }
                    });
            report("started stock projection viewpoint=" + viewpoint
                    + " durationMs=" + durationMs);
            stockProjectionSession.start(viewpoint);
            mainHandler.postDelayed(
                    () -> stopAndFinish("duration elapsed"),
                    durationMs);
            return;
        }

        String displayName = DISPLAY_BASE.equals(displayLayer)
                ? CAMERA_BASE_DISPLAY_NAME
                : CAMERA_OVERLAY_DISPLAY_NAME;
        Display display = findExactCameraDisplay(this, displayName);
        if (display == null) {
            report("failed camera display not found name=" + displayName);
            stopAndFinish("display missing");
            return;
        }

        try {
            presentation = new ProbePresentation(
                    this,
                    display,
                    viewpoint,
                    surfaceView,
                    processing,
                    cropSource,
                    cropWidthPercent,
                    windowMode,
                    vendorTeardownHandler,
                    new ProbePresentation.Listener() {
                        @Override
                        public void onReady(String details) {
                            report("ready " + details);
                        }

                        @Override
                        public void onFirstFrame() {
                            report("frame first buffer received");
                        }

                        @Override
                        public void onFailure(String details) {
                            report("failed " + details);
                            stopAndFinish("renderer failure");
                        }
                    });
            presentation.show();
            report("started displayId=" + display.getDisplayId()
                    + " displayName=" + display.getName()
                    + " viewpoint=" + viewpoint
                    + " renderer=" + (surfaceView ? "SurfaceView" : "TextureView")
                    + " processing=" + processing
                    + " cropSource=" + cropSource
                    + " cropWidthPercent=" + cropWidthPercent
                    + " windowMode=" + windowMode
                    + " displayLayer=" + displayLayer
                    + " durationMs=" + durationMs);
            mainHandler.postDelayed(
                    () -> stopAndFinish("duration elapsed"),
                    durationMs);
        } catch (RuntimeException error) {
            report("failed presentation show " + shortError(error));
            stopAndFinish("presentation failure");
        }
    }

    private void stopAndFinish(String reason) {
        if (stopping) {
            return;
        }
        stopping = true;
        mainHandler.removeCallbacksAndMessages(null);
        ProbePresentation current = presentation;
        presentation = null;
        StockProjectionSession stockCurrent = stockProjectionSession;
        stockProjectionSession = null;
        report("stopping reason=" + reason);
        if (stockCurrent != null) {
            stockCurrent.stop();
        }
        if (current == null) {
            completeStop();
            return;
        }
        current.dismissAfterSurfaceRelease(this::completeStop);
    }

    private void completeStop() {
        mainHandler.post(() -> {
            report("stopped");
            if (vendorTeardownThread != null) {
                vendorTeardownThread.quitSafely();
                vendorTeardownThread = null;
                vendorTeardownHandler = null;
            }
            finishAndRemoveTask();
        });
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        ProbePresentation current = presentation;
        presentation = null;
        StockProjectionSession stockCurrent = stockProjectionSession;
        stockProjectionSession = null;
        if (stockCurrent != null) {
            stockCurrent.stop();
        }
        if (current != null) {
            current.dismissAfterSurfaceRelease(() -> {
                if (vendorTeardownThread != null) {
                    vendorTeardownThread.quitSafely();
                }
            });
        } else if (vendorTeardownThread != null) {
            vendorTeardownThread.quitSafely();
        }
        super.onDestroy();
    }

    private void startVendorThread() {
        vendorTeardownThread = new HandlerThread("night-vision-avc-teardown");
        vendorTeardownThread.start();
        vendorTeardownHandler = new Handler(vendorTeardownThread.getLooper());
    }

    private void configureHostWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = 1;
        params.height = 1;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = -1_000;
        params.y = -1_000;
        params.alpha = 0.0f;
        window.setAttributes(params);
    }

    private void report(String message) {
        String line = "run=" + runId + " " + message;
        Log.i(TAG, line);
        File status = new File(getFilesDir(), "status-" + runId + ".txt");
        synchronized (NightVisionProbeActivity.class) {
            try (FileOutputStream output = new FileOutputStream(status, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
            } catch (IOException error) {
                Log.e(TAG, "status write failed", error);
            }
        }
    }

    private static String sanitizeRunId(String value) {
        if (value == null || value.isEmpty()) {
            return "manual";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String normalizeProcessing(String value) {
        if (value == null || value.isEmpty() || PROCESSING_RAW.equals(value)) {
            return PROCESSING_RAW;
        }
        if (PROCESSING_NIGHT.equals(value) || PROCESSING_NIGHT_ZEBRA.equals(value)) {
            return value;
        }
        return null;
    }

    private static String normalizeCropSource(String value) {
        if (CROP_LEFT.equals(value) || CROP_RIGHT.equals(value)) {
            return value;
        }
        return CROP_NONE;
    }

    private static String normalizeWindowMode(String value) {
        return WINDOW_CENTER.equals(value) ? WINDOW_CENTER : WINDOW_FULL;
    }

    private static String normalizeDisplayLayer(String value) {
        return DISPLAY_BASE.equals(value) ? DISPLAY_BASE : DISPLAY_OVERLAY;
    }

    private static Display findExactCameraDisplay(Context context, String displayName) {
        DisplayManager manager =
                (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            return null;
        }
        for (Display display : manager.getDisplays()) {
            if (displayName.equals(display.getName())) {
                return display;
            }
        }
        return null;
    }

    private static String shortError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : " " + message);
    }

    private static final class ProbePresentation extends Presentation {
        interface Listener {
            void onReady(String details);
            void onFirstFrame();
            void onFailure(String details);
        }

        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final int viewpoint;
        private final boolean useSurfaceView;
        private final String processing;
        private final String cropSource;
        private final int cropWidthPercent;
        private final String windowMode;
        private final int displayWidth;
        private final int cameraWindowWidth;
        private final Handler vendorTeardownHandler;
        private final Listener listener;
        private final Object teardownLock = new Object();
        private final List<Runnable> teardownCallbacks = new ArrayList<>();
        private ProbeRenderer renderer;
        private boolean teardownScheduled;
        private boolean teardownFinished;

        ProbePresentation(
                Context context,
                Display display,
                int viewpoint,
                boolean useSurfaceView,
                String processing,
                String cropSource,
                int cropWidthPercent,
                String windowMode,
                Handler vendorTeardownHandler,
                Listener listener) {
            super(context, display, R.style.ProbeMirrorPresentationTheme);
            this.viewpoint = viewpoint;
            this.useSurfaceView = useSurfaceView;
            this.processing = processing;
            this.cropSource = cropSource;
            this.cropWidthPercent = cropWidthPercent;
            this.windowMode = windowMode;
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            this.displayWidth = Math.max(1, metrics.widthPixels);
            int baseSlotWidth = Math.max(1, displayWidth / 3);
            this.cameraWindowWidth = Math.min(
                    displayWidth,
                    baseSlotWidth + Math.round(baseSlotWidth * 0.20f));
            this.vendorTeardownHandler = vendorTeardownHandler;
            this.listener = listener;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.addFlags(
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            }

            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.TRANSPARENT);
            if (useSurfaceView) {
                SurfaceView surfaceView = new SurfaceView(getContext());
                root.addView(
                        surfaceView,
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                Gravity.CENTER));
                setContentView(root);
                ProbeAvcSurfaceRenderer surfaceRenderer = new ProbeAvcSurfaceRenderer(
                        getContext(),
                        surfaceView,
                        listener);
                renderer = surfaceRenderer;
                surfaceRenderer.start(viewpoint);
                return;
            }

            TextureView textureView = new TextureView(getContext());
            textureView.setOpaque(true);
            textureView.setAlpha(1.0f);
            applyProcessing(textureView, processing);
            FrameLayout cameraFrame = new FrameLayout(getContext());
            cameraFrame.setBackgroundColor(Color.BLACK);
            cameraFrame.setClipChildren(true);
            cameraFrame.setClipToPadding(true);
            int textureWidth = WINDOW_CENTER.equals(windowMode)
                    && !CROP_NONE.equals(cropSource)
                    ? displayWidth
                    : ViewGroup.LayoutParams.MATCH_PARENT;
            cameraFrame.addView(
                    textureView,
                    new FrameLayout.LayoutParams(
                            textureWidth,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.START | Gravity.TOP));
            root.addView(
                    cameraFrame,
                    new FrameLayout.LayoutParams(
                            WINDOW_CENTER.equals(windowMode)
                                    ? cameraWindowWidth
                                    : ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            WINDOW_CENTER.equals(windowMode)
                                    ? Gravity.CENTER_HORIZONTAL | Gravity.TOP
                                    : Gravity.CENTER));
            setContentView(root);
            applyCropTransform(
                    textureView,
                    cropSource,
                    cropWidthPercent,
                    WINDOW_CENTER.equals(windowMode) ? cameraWindowWidth : 0);

            ProbeAvcRenderer textureRenderer = new ProbeAvcRenderer(
                    getContext(),
                    textureView,
                    listener);
            renderer = textureRenderer;
            textureRenderer.start(viewpoint);
        }

        private static void applyCropTransform(
                TextureView view,
                String cropSource,
                int cropWidthPercent,
                int requestedDestinationWidth) {
            if (CROP_NONE.equals(cropSource)) {
                view.setTransform(null);
                return;
            }
            view.addOnLayoutChangeListener(
                    (changedView, left, top, right, bottom,
                            oldLeft, oldTop, oldRight, oldBottom) -> {
                        int width = right - left;
                        int height = bottom - top;
                        if (width <= 0 || height <= 0) {
                            return;
                        }
                        Matrix transform = new Matrix();
                        float sourceFraction = cropWidthPercent / 100f;
                        float sourceLeft = CROP_RIGHT.equals(cropSource)
                                ? width * (1f - sourceFraction)
                                : 0f;
                        float destinationWidth = requestedDestinationWidth > 0
                                ? requestedDestinationWidth
                                : width;
                        float horizontalScale =
                                destinationWidth / (width * sourceFraction);
                        transform.setValues(new float[] {
                                horizontalScale, 0f, -sourceLeft * horizontalScale,
                                0f, 1f, 0f,
                                0f, 0f, 1f,
                        });
                        view.setTransform(transform);
                    });
        }

        private static void applyProcessing(TextureView view, String processing) {
            if (PROCESSING_RAW.equals(processing)) {
                view.setRenderEffect(null);
                return;
            }
            RuntimeShader shader = new RuntimeShader(
                    "uniform shader cameraFrame;\n"
                            + "uniform float zebraEnabled;\n"
                            + "half4 main(float2 p) {\n"
                            + "  half4 src = cameraFrame.eval(p);\n"
                            + "  half luma = dot(src.rgb, half3(0.2126, 0.7152, 0.0722));\n"
                            + "  half curved = pow(max(luma, 0.0), 0.62);\n"
                            + "  half mapped = 0.018 + 0.982 * "
                            + "(curved / (0.78 + 0.22 * curved));\n"
                            + "  half gain = mapped / max(luma, 0.02);\n"
                            + "  half3 lifted = clamp(src.rgb * gain, 0.0, 1.0);\n"
                            + "  lifted = mix(half3(mapped), lifted, 0.88);\n"
                            + "  half zebraMask = smoothstep(0.84, 0.94, luma) "
                            + "* half(zebraEnabled);\n"
                            + "  half stripe = step(0.5, fract((p.x + p.y) * 0.055));\n"
                            + "  half3 zebra = mix(half3(0.04), "
                            + "half3(1.0, 0.82, 0.08), stripe);\n"
                            + "  lifted = mix(lifted, zebra, zebraMask * 0.55);\n"
                            + "  return half4(lifted, src.a);\n"
                            + "}\n");
            shader.setFloatUniform(
                    "zebraEnabled",
                    PROCESSING_NIGHT_ZEBRA.equals(processing) ? 1.0f : 0.0f);
            view.setRenderEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, "cameraFrame"));
        }

        @Override
        public void dismiss() {
            dismissAfterSurfaceRelease(null);
        }

        void dismissAfterSurfaceRelease(Runnable onComplete) {
            synchronized (teardownLock) {
                if (onComplete != null) {
                    teardownCallbacks.add(onComplete);
                }
                if (teardownFinished) {
                    mainHandler.post(this::completeCallbacks);
                    return;
                }
                if (teardownScheduled) {
                    return;
                }
                teardownScheduled = true;
            }

            try {
                super.dismiss();
            } finally {
                vendorTeardownHandler.post(() -> {
                    try {
                        if (renderer != null) {
                            renderer.stop();
                        }
                    } finally {
                        synchronized (teardownLock) {
                            teardownFinished = true;
                        }
                        mainHandler.post(this::completeCallbacks);
                    }
                });
            }
        }

        private void completeCallbacks() {
            List<Runnable> callbacks;
            synchronized (teardownLock) {
                if (!teardownFinished) {
                    return;
                }
                callbacks = new ArrayList<>(teardownCallbacks);
                teardownCallbacks.clear();
            }
            for (Runnable callback : callbacks) {
                callback.run();
            }
        }
    }

    private interface ProbeRenderer {
        void stop();
    }

    private static final class ProbeAvcSurfaceRenderer
            implements ProbeRenderer, SurfaceHolder.Callback {
        private final Context context;
        private final SurfaceView surfaceView;
        private final ProbePresentation.Listener listener;
        private AvcAidlClient client;
        private Surface surface;
        private boolean bound;
        private boolean bindingRequested;
        private boolean initAttempted;
        private boolean stopping;
        private int viewpoint;

        private final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                client = new AvcAidlClient(service);
                bound = true;
                initializeIfReady();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                bound = false;
                bindingRequested = false;
                client = null;
                failIfRunning("com.byd.avc disconnected");
            }

            @Override
            public void onBindingDied(ComponentName name) {
                bound = false;
                bindingRequested = false;
                client = null;
                failIfRunning("com.byd.avc binding died");
            }

            @Override
            public void onNullBinding(ComponentName name) {
                bound = false;
                bindingRequested = false;
                client = null;
                failIfRunning("com.byd.avc returned no binder");
            }
        };

        ProbeAvcSurfaceRenderer(
                Context context,
                SurfaceView surfaceView,
                ProbePresentation.Listener listener) {
            this.context = context;
            this.surfaceView = surfaceView;
            this.listener = listener;
        }

        void start(int viewpoint) {
            this.viewpoint = viewpoint;
            surfaceView.getHolder().addCallback(this);
            Surface existing = surfaceView.getHolder().getSurface();
            if (existing != null && existing.isValid()) {
                surface = existing;
            }
            Intent intent = new Intent("com.byd.avc.aidl.service")
                    .setPackage("com.byd.avc");
            try {
                bindingRequested =
                        context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!bindingRequested) {
                    listener.onFailure("com.byd.avc bind returned false");
                }
            } catch (RuntimeException error) {
                listener.onFailure("com.byd.avc bind failed " + shortError(error));
            }
        }

        @Override
        public void stop() {
            stopping = true;
            surfaceView.getHolder().removeCallback(this);
            AvcAidlClient current = client;
            if (current != null && initAttempted) {
                try {
                    current.freeDisplay();
                } catch (RemoteException | RuntimeException error) {
                    Log.i(TAG, "vendor freeDisplay failed " + shortError(error));
                }
            }
            if (bound || bindingRequested) {
                try {
                    context.unbindService(connection);
                } catch (IllegalArgumentException ignored) {
                    // The vendor process may already have removed the binding.
                }
            }
            bound = false;
            bindingRequested = false;
            client = null;
            surface = null;
            initAttempted = false;
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            surface = holder.getSurface();
            initializeIfReady();
        }

        @Override
        public void surfaceChanged(
                SurfaceHolder holder,
                int format,
                int width,
                int height) {
            surface = holder.getSurface();
            initializeIfReady();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (surface == holder.getSurface()) {
                surface = null;
            }
        }

        private void initializeIfReady() {
            if (stopping
                    || !bound
                    || client == null
                    || surface == null
                    || !surface.isValid()
                    || initAttempted) {
                return;
            }
            initAttempted = true;
            try {
                String name = client.getName();
                int bufferType = client.getSupportPushBufferType();
                boolean initialized = client.initDisplay(surface);
                if (!initialized) {
                    listener.onFailure(
                            "SurfaceView initDisplay returned false buffer=" + bufferType);
                    return;
                }
                client.setViewpoint(viewpoint);
                listener.onReady(
                        "avc=" + name
                                + " init=true"
                                + " buffer=" + bufferType
                                + " viewpoint=" + viewpoint
                                + " renderer=SurfaceView");
            } catch (RemoteException | RuntimeException error) {
                listener.onFailure("AVC SurfaceView init failed " + shortError(error));
            }
        }

        private void failIfRunning(String details) {
            if (!stopping) {
                listener.onFailure(details);
            }
        }
    }

    /**
     * Uses the stock PIP path to make the cluster projection visible, then asks
     * the already-running AVC renderer to select the physical front camera.
     * Keeping the Surface in the system AVC process avoids two clients racing
     * to own the vendor native window.
     */
    private static final class StockProjectionSession {
        interface Listener {
            void onReady(String details);
            void onFailure(String details);
        }

        private static final String ACTION_AUTO_VIDEO_BUTTON =
                "android.intent.action.AUTO_VIDEO_BUTTON";
        private static final String EXTRA_KEY_EVENT =
                "android.intent.extra.KEY_EVENT";
        private static final int KEY_OPEN_PIP_LEFT = 3040;
        private static final int KEY_CLOSE_PIP = 3043;
        private static final long VIEWPOINT_DELAY_MS = 900L;

        private final Context context;
        private final Listener listener;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private AvcAidlClient client;
        private boolean bound;
        private boolean bindingRequested;
        private boolean stopping;
        private int viewpoint;

        private final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                client = new AvcAidlClient(service);
                bound = true;
                mainHandler.postDelayed(
                        StockProjectionSession.this::selectFrontViewpoint,
                        VIEWPOINT_DELAY_MS);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                bound = false;
                bindingRequested = false;
                client = null;
                failIfRunning("com.byd.avc disconnected");
            }

            @Override
            public void onBindingDied(ComponentName name) {
                bound = false;
                bindingRequested = false;
                client = null;
                failIfRunning("com.byd.avc binding died");
            }

            @Override
            public void onNullBinding(ComponentName name) {
                bound = false;
                bindingRequested = false;
                client = null;
                failIfRunning("com.byd.avc returned no binder");
            }
        };

        StockProjectionSession(Context context, Listener listener) {
            this.context = context;
            this.listener = listener;
        }

        void start(int viewpoint) {
            this.viewpoint = viewpoint;
            sendStockKey(KEY_OPEN_PIP_LEFT);
            Intent intent = new Intent("com.byd.avc.aidl.service")
                    .setPackage("com.byd.avc");
            try {
                bindingRequested =
                        context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!bindingRequested) {
                    listener.onFailure("com.byd.avc bind returned false");
                }
            } catch (RuntimeException error) {
                listener.onFailure("com.byd.avc bind failed " + shortError(error));
            }
        }

        public void stop() {
            if (stopping) {
                return;
            }
            stopping = true;
            mainHandler.removeCallbacksAndMessages(null);
            sendStockKey(KEY_CLOSE_PIP);
            if (bound || bindingRequested) {
                try {
                    context.unbindService(connection);
                } catch (IllegalArgumentException ignored) {
                    // The vendor process may already have removed the binding.
                }
            }
            bound = false;
            bindingRequested = false;
            client = null;
        }

        private void selectFrontViewpoint() {
            AvcAidlClient current = client;
            if (stopping || current == null) {
                return;
            }
            try {
                current.setViewpoint(viewpoint);
                listener.onReady(
                        "stock PIP route selected viewpoint=" + viewpoint);
            } catch (RemoteException | RuntimeException error) {
                listener.onFailure(
                        "stock route viewpoint failed " + shortError(error));
            }
        }

        private void sendStockKey(int keyCode) {
            Intent intent = new Intent(ACTION_AUTO_VIDEO_BUTTON)
                    .setPackage("com.byd.avc")
                    .putExtra(EXTRA_KEY_EVENT, keyCode);
            context.sendBroadcast(intent);
        }

        private void failIfRunning(String details) {
            if (!stopping) {
                listener.onFailure(details);
            }
        }
    }

    private static final class ProbeAvcRenderer
            implements ProbeRenderer, TextureView.SurfaceTextureListener {
        private static final long FIRST_REASSERT_DELAY_MS = 250L;
        private static final long SECOND_REASSERT_DELAY_MS = 900L;

        interface Listener {
            void onReady(String details);
            void onFirstFrame();
            void onFailure(String details);
        }

        private final Context context;
        private final TextureView textureView;
        private final Listener listener;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private AvcAidlClient client;
        private boolean bound;
        private boolean bindingRequested;
        private boolean initAttempted;
        private boolean displayInitialized;
        private boolean firstFrameReported;
        private boolean stopping;
        private int viewpoint;
        private Surface surface;

        private final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                client = new AvcAidlClient(service);
                bound = true;
                initializeIfReady();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                bound = false;
                bindingRequested = false;
                client = null;
                failIfRunning("com.byd.avc disconnected");
            }

            @Override
            public void onBindingDied(ComponentName name) {
                bound = false;
                bindingRequested = false;
                client = null;
                failIfRunning("com.byd.avc binding died");
            }

            @Override
            public void onNullBinding(ComponentName name) {
                bound = false;
                bindingRequested = false;
                client = null;
                failIfRunning("com.byd.avc returned no binder");
            }
        };

        ProbeAvcRenderer(
                Context context,
                TextureView textureView,
                ProbePresentation.Listener presentationListener) {
            this.context = context;
            this.textureView = textureView;
            this.listener = new Listener() {
                @Override
                public void onReady(String details) {
                    presentationListener.onReady(details);
                }

                @Override
                public void onFirstFrame() {
                    presentationListener.onFirstFrame();
                }

                @Override
                public void onFailure(String details) {
                    presentationListener.onFailure(details);
                }
            };
        }

        void start(int viewpoint) {
            this.viewpoint = viewpoint;
            firstFrameReported = false;
            textureView.setSurfaceTextureListener(this);
            if (textureView.isAvailable() && textureView.getSurfaceTexture() != null) {
                onSurfaceTextureAvailable(
                        textureView.getSurfaceTexture(),
                        textureView.getWidth(),
                        textureView.getHeight());
            }
            Intent intent = new Intent("com.byd.avc.aidl.service")
                    .setPackage("com.byd.avc");
            try {
                bindingRequested =
                        context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!bindingRequested) {
                    listener.onFailure("com.byd.avc bind returned false");
                }
            } catch (RuntimeException error) {
                listener.onFailure("com.byd.avc bind failed " + shortError(error));
            }
        }

        @Override
        public void stop() {
            stopping = true;
            mainHandler.removeCallbacksAndMessages(null);
            AvcAidlClient current = client;
            if (current != null && displayInitialized) {
                try {
                    current.freeDisplay();
                } catch (RemoteException | RuntimeException error) {
                    Log.i(TAG, "vendor freeDisplay failed " + shortError(error));
                }
            }
            if (bound || bindingRequested) {
                try {
                    context.unbindService(connection);
                } catch (IllegalArgumentException ignored) {
                    // The vendor process may already have removed the binding.
                }
            }
            bound = false;
            bindingRequested = false;
            client = null;
            initAttempted = false;
            displayInitialized = false;
            releaseSurface();
        }

        @Override
        public void onSurfaceTextureAvailable(
                SurfaceTexture texture,
                int width,
                int height) {
            texture.setDefaultBufferSize(Math.max(1, width), Math.max(1, height));
            releaseSurface();
            surface = new Surface(texture);
            initializeIfReady();
        }

        @Override
        public void onSurfaceTextureSizeChanged(
                SurfaceTexture texture,
                int width,
                int height) {
            texture.setDefaultBufferSize(Math.max(1, width), Math.max(1, height));
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            releaseSurface();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            if (!firstFrameReported) {
                firstFrameReported = true;
                listener.onFirstFrame();
                mainHandler.postDelayed(
                        this::reassertViewpoint,
                        FIRST_REASSERT_DELAY_MS);
                mainHandler.postDelayed(
                        this::reassertViewpoint,
                        SECOND_REASSERT_DELAY_MS);
            }
        }

        private void reassertViewpoint() {
            AvcAidlClient current = client;
            if (stopping || !displayInitialized || current == null) {
                return;
            }
            try {
                current.setViewpoint(viewpoint);
                Log.i(TAG, "reasserted viewpoint=" + viewpoint);
            } catch (RemoteException | RuntimeException error) {
                failIfRunning("AVC viewpoint reassert failed " + shortError(error));
            }
        }

        private void initializeIfReady() {
            if (stopping
                    || !bound
                    || client == null
                    || surface == null
                    || !surface.isValid()
                    || initAttempted) {
                return;
            }
            initAttempted = true;
            try {
                String name = client.getName();
                int bufferType = client.getSupportPushBufferType();
                boolean initialized = client.initDisplay(surface);
                if (!initialized) {
                    listener.onFailure(
                            "initDisplay returned false buffer=" + bufferType);
                    return;
                }
                displayInitialized = true;
                client.setViewpoint(viewpoint);
                listener.onReady(
                        "avc=" + name
                                + " init=true"
                                + " buffer=" + bufferType
                                + " viewpoint=" + viewpoint);
            } catch (RemoteException | RuntimeException error) {
                listener.onFailure("AVC init failed " + shortError(error));
            }
        }

        private void failIfRunning(String details) {
            if (!stopping) {
                listener.onFailure(details);
            }
        }

        private void releaseSurface() {
            if (surface != null) {
                surface.release();
                surface = null;
            }
        }
    }

    private static final class AvcAidlClient {
        private static final String DESCRIPTOR =
                "com.byd.avc.aidl.IAVCAidlInterface";
        private static final int TRANSACTION_GET_NAME = 1;
        private static final int TRANSACTION_SET_VIEWPOINT = 5;
        private static final int TRANSACTION_GET_SUPPORT_PUSH_BUFFER_TYPE = 6;
        private static final int TRANSACTION_INIT_DISPLAY = 7;
        private static final int TRANSACTION_FREE_DISPLAY = 9;

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
                remote.transact(
                        TRANSACTION_GET_SUPPORT_PUSH_BUFFER_TYPE,
                        data,
                        reply,
                        0);
                reply.readException();
                return reply.readInt();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        boolean initDisplay(Surface value) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(1);
                value.writeToParcel(data, 0);
                remote.transact(TRANSACTION_INIT_DISPLAY, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        void setViewpoint(int value) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(value);
                remote.transact(TRANSACTION_SET_VIEWPOINT, data, reply, 0);
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
