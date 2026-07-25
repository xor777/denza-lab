package dev.denza.nightvision.probe;

import android.Manifest;
import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.Collections;

/**
 * Raw Camera2 evaluation of the BYD DVR source on the instrument display.
 *
 * The source is rendered without tone mapping or digital crop. The transparent
 * Presentation root keeps the existing cluster scene visible around the same
 * centered, opaque camera frame used by the AVC source evaluation.
 */
public final class DvrCameraProbeActivity extends Activity {
    private static final String TAG = "DvrCameraProbe";
    private static final String CAMERA_DISPLAY_NAME =
            "shared_fission_bg_XDJAScreenProjection_1";
    private static final String CAMERA_ID = "0";
    private static final long DEFAULT_DURATION_MS = 60_000L;
    private static final long MIN_DURATION_MS = 1_000L;
    private static final long MAX_DURATION_MS = 120_000L;
    private static final String EXTRA_DURATION_MS = "duration_ms";
    private static final String EXTRA_FINISH = "finish";
    private static final String EXTRA_VERTICAL_SCALE = "vertical_scale";
    private static final String EXTRA_ZOOM = "zoom";
    private static final String EXTRA_SHADOWS = "shadows";
    private static final String EXTRA_SHADOW_STRENGTH = "shadow_strength";
    private static final String EXTRA_SHADOW_CUTOFF = "shadow_cutoff";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private DvrPresentation presentation;
    private boolean stopping;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureHostWindow();
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
        if (presentation != null || stopping) {
            Log.i(TAG, "refused duplicate start");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "failed camera permission denied");
            finishAndRemoveTask();
            return;
        }

        Display display = findExactDisplay(this, CAMERA_DISPLAY_NAME);
        if (display == null) {
            Log.i(TAG, "failed display missing name=" + CAMERA_DISPLAY_NAME);
            finishAndRemoveTask();
            return;
        }

        long durationMs = Math.max(
                MIN_DURATION_MS,
                Math.min(
                        MAX_DURATION_MS,
                        intent.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)));
        float verticalScale = Math.max(
                0.5f,
                Math.min(3.0f, intent.getFloatExtra(EXTRA_VERTICAL_SCALE, 1.0f)));
        float zoom = Math.max(
                1.0f,
                Math.min(4.0f, intent.getFloatExtra(EXTRA_ZOOM, 1.0f)));
        boolean shadows = intent.getBooleanExtra(EXTRA_SHADOWS, false);
        float shadowStrength = Math.max(
                0.0f,
                Math.min(
                        1.0f,
                        intent.getFloatExtra(EXTRA_SHADOW_STRENGTH, 0.90f)));
        float shadowCutoff = Math.max(
                0.20f,
                Math.min(
                        0.70f,
                        intent.getFloatExtra(EXTRA_SHADOW_CUTOFF, 0.42f)));
        try {
            presentation = new DvrPresentation(
                    this,
                    display,
                    verticalScale,
                    zoom,
                    shadows,
                    shadowStrength,
                    shadowCutoff,
                    new DvrPresentation.Listener() {
                        @Override
                        public void onReady(Size previewSize, int frameWidth, int frameHeight) {
                            Log.i(
                                    TAG,
                                    "ready cameraId=" + CAMERA_ID
                                            + " preview=" + previewSize
                                            + " frame=" + frameWidth + "x" + frameHeight
                                            + " displayId=" + display.getDisplayId());
                        }

                        @Override
                        public void onFirstFrame() {
                            Log.i(TAG, "frame first buffer received");
                        }

                        @Override
                        public void onFailure(String details) {
                            Log.i(TAG, "failed " + details);
                            stopAndFinish("renderer failure");
                        }
                    });
            presentation.show();
            Log.i(
                            TAG,
                            "started cameraId=" + CAMERA_ID
                            + " displayId=" + display.getDisplayId()
                            + " displayName=" + display.getName()
                            + " verticalScale=" + verticalScale
                            + " zoom=" + zoom
                            + " shadows=" + shadows
                            + " shadowStrength=" + shadowStrength
                            + " shadowCutoff=" + shadowCutoff
                            + " durationMs=" + durationMs);
            mainHandler.postDelayed(
                    () -> stopAndFinish("duration elapsed"),
                    durationMs);
        } catch (RuntimeException error) {
            Log.i(TAG, "failed presentation show " + shortError(error));
            stopAndFinish("presentation failure");
        }
    }

    private void stopAndFinish(String reason) {
        if (stopping) {
            return;
        }
        stopping = true;
        mainHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "stopping reason=" + reason);
        DvrPresentation current = presentation;
        presentation = null;
        if (current != null) {
            current.dismiss();
        }
        Log.i(TAG, "stopped");
        finishAndRemoveTask();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        DvrPresentation current = presentation;
        presentation = null;
        if (current != null) {
            current.dismiss();
        }
        super.onDestroy();
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

    private static Display findExactDisplay(Context context, String displayName) {
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

    private static final class DvrPresentation extends Presentation {
        interface Listener {
            void onReady(Size previewSize, int frameWidth, int frameHeight);

            void onFirstFrame();

            void onFailure(String details);
        }

        private final String cameraId = CAMERA_ID;
        private final Listener listener;
        private final int frameWidth;
        private final int frameHeight;
        private final float verticalScale;
        private final float zoom;
        private final boolean shadows;
        private final float shadowStrength;
        private final float shadowCutoff;
        private HandlerThread cameraThread;
        private Handler cameraHandler;
        private TextureView textureView;
        private CameraDevice cameraDevice;
        private CameraCaptureSession captureSession;
        private Surface previewSurface;
        private boolean firstFrameReported;
        private boolean stopping;

        DvrPresentation(
                Context context,
                Display display,
                float verticalScale,
                float zoom,
                boolean shadows,
                float shadowStrength,
                float shadowCutoff,
                Listener listener) {
            super(context, display, R.style.ProbeMirrorPresentationTheme);
            this.listener = listener;
            this.verticalScale = verticalScale;
            this.zoom = zoom;
            this.shadows = shadows;
            this.shadowStrength = shadowStrength;
            this.shadowCutoff = shadowCutoff;
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            int baseSlotWidth = Math.max(1, metrics.widthPixels / 3);
            frameWidth = Math.min(
                    metrics.widthPixels,
                    baseSlotWidth + Math.round(baseSlotWidth * 0.20f));
            frameHeight = Math.max(1, metrics.heightPixels);
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

            FrameLayout cameraFrame = new FrameLayout(getContext());
            cameraFrame.setBackgroundColor(Color.BLACK);
            cameraFrame.setClipChildren(true);
            cameraFrame.setClipToPadding(true);
            root.addView(
                    cameraFrame,
                    new FrameLayout.LayoutParams(
                            frameWidth,
                            frameHeight,
                            Gravity.CENTER));

            textureView = new TextureView(getContext());
            textureView.setOpaque(true);
            textureView.setAlpha(1.0f);
            applyShadowProcessing(
                    textureView,
                    shadows,
                    shadowStrength,
                    shadowCutoff);
            cameraFrame.addView(
                    textureView,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER));

            setContentView(root);
            startCameraThread();
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        SurfaceTexture surfaceTexture,
                        int width,
                        int height) {
                    openCamera(surfaceTexture);
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        SurfaceTexture surfaceTexture,
                        int width,
                        int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                    closeCamera();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                    if (!firstFrameReported) {
                        firstFrameReported = true;
                        listener.onFirstFrame();
                    }
                }
            });
        }

        @Override
        public void dismiss() {
            stopping = true;
            closeCamera();
            stopCameraThread();
            super.dismiss();
        }

        private void startCameraThread() {
            cameraThread = new HandlerThread("DvrCameraProbe");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }

        private void stopCameraThread() {
            HandlerThread thread = cameraThread;
            cameraThread = null;
            cameraHandler = null;
            if (thread == null) {
                return;
            }
            thread.quitSafely();
            try {
                thread.join(1_000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        private void openCamera(SurfaceTexture surfaceTexture) {
            Context context = getContext();
            if (context.checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                listener.onFailure("camera permission denied");
                return;
            }
            CameraManager cameraManager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) {
                listener.onFailure("camera service missing");
                return;
            }
            try {
                Size previewSize = choosePreviewSize(cameraManager);
                Rect cropRegion = chooseCropRegion(cameraManager);
                surfaceTexture.setDefaultBufferSize(
                        previewSize.getWidth(),
                        previewSize.getHeight());
                layoutTextureView(previewSize);
                cameraManager.openCamera(
                        cameraId,
                        new CameraDevice.StateCallback() {
                            @Override
                            public void onOpened(CameraDevice camera) {
                                if (stopping) {
                                    camera.close();
                                    return;
                                }
                                cameraDevice = camera;
                                Log.i(TAG, "camera opened id=" + cameraId);
                                createPreviewSession(
                                        surfaceTexture,
                                        previewSize,
                                        cropRegion);
                            }

                            @Override
                            public void onDisconnected(CameraDevice camera) {
                                camera.close();
                                cameraDevice = null;
                                if (!stopping) {
                                    listener.onFailure("camera disconnected id=" + cameraId);
                                }
                            }

                            @Override
                            public void onError(CameraDevice camera, int error) {
                                camera.close();
                                cameraDevice = null;
                                if (!stopping) {
                                    listener.onFailure(
                                            "camera error id=" + cameraId + " code=" + error);
                                }
                            }
                        },
                        cameraHandler);
            } catch (CameraAccessException | IllegalArgumentException
                    | SecurityException error) {
                listener.onFailure("camera open " + shortError(error));
            }
        }

        private Size choosePreviewSize(CameraManager cameraManager)
                throws CameraAccessException {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map =
                    characteristics.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return new Size(1920, 1080);
            }
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) {
                return new Size(1920, 1080);
            }
            for (Size size : sizes) {
                if (size.getWidth() == 1920 && size.getHeight() == 1080) {
                    return size;
                }
            }
            Size best = sizes[0];
            long bestPixels = 0L;
            for (Size size : sizes) {
                long pixels = (long) size.getWidth() * size.getHeight();
                if (size.getWidth() <= 1920
                        && size.getHeight() <= 1080
                        && pixels > bestPixels) {
                    best = size;
                    bestPixels = pixels;
                }
            }
            return best;
        }

        private Rect chooseCropRegion(CameraManager cameraManager)
                throws CameraAccessException {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);
            Rect activeArray = characteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (activeArray == null || zoom <= 1.0f) {
                return null;
            }
            int cropWidth = Math.max(2, Math.round(activeArray.width() / zoom));
            int cropHeight = Math.max(2, Math.round(activeArray.height() / zoom));
            cropWidth -= cropWidth % 2;
            cropHeight -= cropHeight % 2;
            int left = activeArray.centerX() - cropWidth / 2;
            int top = activeArray.centerY() - cropHeight / 2;
            Rect cropRegion = new Rect(
                    left,
                    top,
                    left + cropWidth,
                    top + cropHeight);
            Log.i(
                    TAG,
                    "crop active=" + activeArray
                            + " zoom=" + zoom
                            + " region=" + cropRegion);
            return cropRegion;
        }

        private void layoutTextureView(Size previewSize) {
            textureView.setLayoutParams(
                    new FrameLayout.LayoutParams(
                            frameWidth,
                            frameHeight,
                            Gravity.CENTER));
            Matrix transform = new Matrix();
            transform.setScale(
                    zoom,
                    verticalScale * zoom,
                    frameWidth / 2.0f,
                    frameHeight / 2.0f);
            textureView.setTransform(transform);
            Log.i(
                    TAG,
                    "layout frame=" + frameWidth + "x" + frameHeight
                            + " texture=" + frameWidth + "x" + frameHeight
                            + " verticalScale=" + verticalScale
                            + " renderZoom=" + zoom
                            + " preview=" + previewSize);
        }

        private static void applyShadowProcessing(
                TextureView view,
                boolean enabled,
                float strength,
                float cutoff) {
            if (!enabled) {
                view.setRenderEffect(null);
                return;
            }
            RuntimeShader shader = new RuntimeShader(
                    "uniform shader cameraFrame;\n"
                            + "uniform float shadowStrength;\n"
                            + "uniform float shadowCutoff;\n"
                            + "half4 main(float2 p) {\n"
                            + "  half4 src = cameraFrame.eval(p);\n"
                            + "  half luma = dot(src.rgb, "
                            + "half3(0.2126, 0.7152, 0.0722));\n"
                            + "  half cutoff = half(shadowCutoff);\n"
                            + "  half mask = 1.0 - smoothstep("
                            + "cutoff * 0.45, cutoff, luma);\n"
                            + "  half target = pow(max(luma, 0.002), 0.48);\n"
                            + "  half mapped = mix(luma, target, "
                            + "mask * half(shadowStrength));\n"
                            + "  half gain = mapped / max(luma, 0.02);\n"
                            + "  half3 chroma = src.rgb * gain;\n"
                            + "  half colorConfidence = "
                            + "smoothstep(0.015, 0.09, luma);\n"
                            + "  half3 lifted = mix("
                            + "half3(mapped), chroma, colorConfidence);\n"
                            + "  return half4(clamp(lifted, 0.0, 1.0), src.a);\n"
                            + "}\n");
            shader.setFloatUniform("shadowStrength", strength);
            shader.setFloatUniform("shadowCutoff", cutoff);
            view.setRenderEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, "cameraFrame"));
        }

        private void createPreviewSession(
                SurfaceTexture surfaceTexture,
                Size previewSize,
                Rect cropRegion) {
            CameraDevice camera = cameraDevice;
            if (camera == null || stopping) {
                return;
            }
            previewSurface = new Surface(surfaceTexture);
            try {
                CaptureRequest.Builder request =
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                request.addTarget(previewSurface);
                if (cropRegion != null) {
                    request.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
                }
                camera.createCaptureSession(
                        Collections.singletonList(previewSurface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                if (stopping) {
                                    session.close();
                                    return;
                                }
                                captureSession = session;
                                try {
                                    session.setRepeatingRequest(
                                            request.build(),
                                            null,
                                            cameraHandler);
                                    listener.onReady(previewSize, frameWidth, frameHeight);
                                } catch (CameraAccessException
                                        | IllegalStateException error) {
                                    listener.onFailure(
                                            "preview start " + shortError(error));
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                session.close();
                                if (!stopping) {
                                    listener.onFailure("preview configure failed");
                                }
                            }
                        },
                        cameraHandler);
            } catch (CameraAccessException | IllegalStateException error) {
                listener.onFailure("preview session " + shortError(error));
            }
        }

        private void closeCamera() {
            CameraCaptureSession session = captureSession;
            captureSession = null;
            if (session != null) {
                session.close();
            }
            CameraDevice camera = cameraDevice;
            cameraDevice = null;
            if (camera != null) {
                camera.close();
            }
            Surface surface = previewSurface;
            previewSurface = null;
            if (surface != null) {
                surface.release();
            }
        }
    }
}
