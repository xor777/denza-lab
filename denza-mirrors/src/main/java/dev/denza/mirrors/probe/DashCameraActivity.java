package dev.denza.mirrors.probe;

import android.Manifest;
import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Collections;

public class DashCameraActivity extends Activity {
    private static final String TAG = "DenzaProjectionProbe";
    private static final String CLUSTER_DISPLAY_NAME = "shared_fission_bg_XDJAScreenProjection_0";
    private static final int DEFAULT_DISPLAY_ID = 3;
    private static final long DEFAULT_DURATION_MS = 12000L;
    private static final String DEFAULT_SLOT = "left";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private CameraPresentation presentation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        String cameraId = getIntent().getStringExtra("camera_id");
        if (cameraId == null) {
            cameraId = "3";
        }
        int requestedDisplayId = getIntent().getIntExtra("display_id", DEFAULT_DISPLAY_ID);
        long durationMs = getIntent().getLongExtra("duration_ms", DEFAULT_DURATION_MS);
        String slot = normalizeSlot(getIntent().getStringExtra("slot"));
        int previewWidth = getIntent().getIntExtra("preview_width", 0);
        int previewHeight = getIntent().getIntExtra("preview_height", 0);
        float zoomY = getIntent().getFloatExtra("zoom_y", 1f);
        Display display = findDisplay(requestedDisplayId);
        if (display == null) {
            Log.i(TAG, "dash camera display not found id=" + requestedDisplayId);
            finishAndRemoveTask();
            return;
        }

        try {
            presentation = new CameraPresentation(this, display, cameraId, slot,
                    previewWidth, previewHeight, zoomY);
            presentation.show();
            Log.i(TAG, "dash camera shown cameraId=" + cameraId
                    + " slot=" + slot
                    + " requestedPreview=" + previewWidth + "x" + previewHeight
                    + " zoomY=" + zoomY
                    + " displayId=" + display.getDisplayId() + " name=" + display.getName());
        } catch (RuntimeException e) {
            Log.i(TAG, "dash camera show failed: " + shortError(e));
            finishAndRemoveTask();
            return;
        }

        handler.postDelayed(this::finishAndRemoveTask, durationMs);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        super.onDestroy();
    }

    private Display findDisplay(int requestedDisplayId) {
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
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
        if ("right".equals(slot) || "center".equals(slot) || "full".equals(slot)) {
            return slot;
        }
        return DEFAULT_SLOT;
    }

    private static String shortError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + " " + message;
    }

    private static final class CameraPresentation extends Presentation {
        private final String cameraId;
        private final String slot;
        private final int requestedPreviewWidth;
        private final int requestedPreviewHeight;
        private final float zoomY;
        private FrameLayout previewFrame;
        private TextureView textureView;
        private TextView statusView;
        private HandlerThread cameraThread;
        private Handler cameraHandler;
        private CameraDevice cameraDevice;
        private CameraCaptureSession captureSession;

        CameraPresentation(Context outerContext, Display display, String cameraId, String slot,
                int requestedPreviewWidth, int requestedPreviewHeight, float zoomY) {
            super(outerContext, display);
            this.cameraId = cameraId;
            this.slot = slot;
            this.requestedPreviewWidth = requestedPreviewWidth;
            this.requestedPreviewHeight = requestedPreviewHeight;
            this.zoomY = Math.max(0.5f, Math.min(3f, zoomY));
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.BLACK);

            previewFrame = new FrameLayout(getContext());
            previewFrame.setClipChildren(true);
            previewFrame.setBackgroundColor(Color.BLACK);
            root.addView(previewFrame, buildPreviewFrameParams());

            textureView = new TextureView(getContext());
            previewFrame.addView(textureView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
            ));

            statusView = new TextView(getContext());
            statusView.setText("camera " + cameraId + " " + slot + " y" + this.zoomY);
            statusView.setTextColor(Color.WHITE);
            statusView.setTextSize(22);
            statusView.setGravity(Gravity.CENTER);
            statusView.setBackgroundColor(Color.argb(150, 0, 0, 0));
            FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START
            );
            statusParams.setMargins(18, 18, 18, 18);
            previewFrame.addView(statusView, statusParams);

            setContentView(root);
            startCameraThread();
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    openCamera(surface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    closeCamera();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            });
        }

        private FrameLayout.LayoutParams buildPreviewFrameParams() {
            if ("full".equals(slot)) {
                return new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                );
            }

            DisplayMetrics metrics = new DisplayMetrics();
            getDisplay().getRealMetrics(metrics);
            int slotWidth = Math.max(1, metrics.widthPixels / 3);
            int gravity = Gravity.START;
            if ("right".equals(slot)) {
                gravity = Gravity.END;
            } else if ("center".equals(slot)) {
                gravity = Gravity.CENTER_HORIZONTAL;
            }
            return new FrameLayout.LayoutParams(
                    slotWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    gravity | Gravity.TOP
            );
        }

        @Override
        public void dismiss() {
            closeCamera();
            stopCameraThread();
            super.dismiss();
        }

        private void startCameraThread() {
            cameraThread = new HandlerThread("DenzaDashCamera");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }

        private void stopCameraThread() {
            if (cameraThread == null) {
                return;
            }
            cameraThread.quitSafely();
            try {
                cameraThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }

        private void openCamera(SurfaceTexture surfaceTexture) {
            Context context = getContext();
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                setStatus("camera " + cameraId + " permission denied");
                Log.i(TAG, "dash camera permission denied");
                return;
            }

            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) {
                setStatus("camera service missing");
                return;
            }
            try {
                Size previewSize = choosePreviewSize(cameraManager, cameraId, getTargetAspect());
                surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                layoutTextureView(previewSize);
                cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        cameraDevice = camera;
                        Log.i(TAG, "dash camera opened id=" + cameraId);
                        createPreviewSession(surfaceTexture);
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        Log.i(TAG, "dash camera disconnected id=" + cameraId);
                        camera.close();
                        cameraDevice = null;
                        setStatus("camera " + cameraId + " disconnected");
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        Log.i(TAG, "dash camera error id=" + cameraId + " error=" + error);
                        camera.close();
                        cameraDevice = null;
                        setStatus("camera " + cameraId + " error " + error);
                    }
                }, cameraHandler);
            } catch (CameraAccessException | RuntimeException e) {
                Log.i(TAG, "dash camera open failed id=" + cameraId + ": " + shortError(e));
                setStatus("camera " + cameraId + " open failed");
            }
        }

        private float getTargetAspect() {
            if (previewFrame != null && previewFrame.getWidth() > 0 && previewFrame.getHeight() > 0) {
                return previewFrame.getWidth() / (float) previewFrame.getHeight();
            }
            DisplayMetrics metrics = new DisplayMetrics();
            getDisplay().getRealMetrics(metrics);
            if ("full".equals(slot)) {
                return metrics.widthPixels / (float) metrics.heightPixels;
            }
            return (metrics.widthPixels / 3f) / metrics.heightPixels;
        }

        private Size choosePreviewSize(CameraManager cameraManager, String cameraId, float targetAspect)
                throws CameraAccessException {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return new Size(1280, 720);
            }
            Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) {
                return new Size(1280, 720);
            }
            if (requestedPreviewWidth > 0 && requestedPreviewHeight > 0) {
                for (Size size : sizes) {
                    if (size.getWidth() == requestedPreviewWidth && size.getHeight() == requestedPreviewHeight) {
                        Log.i(TAG, "dash camera forced preview id=" + cameraId
                                + " preview=" + size.getWidth() + "x" + size.getHeight());
                        return size;
                    }
                }
                Log.i(TAG, "dash camera requested preview unavailable id=" + cameraId
                        + " requested=" + requestedPreviewWidth + "x" + requestedPreviewHeight);
            }
            Size best = sizes[0];
            float bestScore = Float.MAX_VALUE;
            for (Size size : sizes) {
                if (size.getWidth() > 1920 || size.getHeight() > 1080) {
                    continue;
                }
                float aspect = size.getWidth() / (float) size.getHeight();
                float aspectPenalty = Math.abs(aspect - targetAspect) * 1000f;
                float sizeBonus = Math.min(size.getWidth() * size.getHeight(), 1920 * 1080) / 1000000f;
                float score = aspectPenalty - sizeBonus;
                if (score < bestScore) {
                    bestScore = score;
                    best = size;
                }
            }
            Log.i(TAG, "dash camera chosen preview id=" + cameraId
                    + " slot=" + slot
                    + " targetAspect=" + targetAspect
                    + " preview=" + best.getWidth() + "x" + best.getHeight());
            return best;
        }

        private void layoutTextureView(Size previewSize) {
            if (previewFrame == null || textureView == null) {
                return;
            }
            previewFrame.post(() -> {
                int frameWidth = previewFrame.getWidth();
                int frameHeight = previewFrame.getHeight();
                if (frameWidth <= 0 || frameHeight <= 0) {
                    return;
                }
                float previewAspect = previewSize.getWidth() / (float) previewSize.getHeight();
                float frameAspect = frameWidth / (float) frameHeight;
                int viewWidth;
                int viewHeight;
                if (frameAspect > previewAspect) {
                    viewWidth = frameWidth;
                    viewHeight = Math.round(frameWidth / previewAspect);
                } else {
                    viewHeight = frameHeight;
                    viewWidth = Math.round(frameHeight * previewAspect);
                }
                viewHeight = Math.max(1, Math.round(viewHeight * zoomY));
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        viewWidth,
                        viewHeight,
                        Gravity.CENTER
                );
                textureView.setLayoutParams(params);
                Log.i(TAG, "dash camera layout id=" + cameraId
                        + " slot=" + slot
                        + " frame=" + frameWidth + "x" + frameHeight
                        + " texture=" + viewWidth + "x" + viewHeight
                        + " zoomY=" + zoomY
                        + " preview=" + previewSize.getWidth() + "x" + previewSize.getHeight());
            });
        }

        private void createPreviewSession(SurfaceTexture surfaceTexture) {
            CameraDevice camera = cameraDevice;
            if (camera == null) {
                return;
            }
            Surface surface = new Surface(surfaceTexture);
            try {
                CaptureRequest.Builder requestBuilder =
                        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                requestBuilder.addTarget(surface);
                camera.createCaptureSession(Collections.singletonList(surface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                captureSession = session;
                                try {
                                    session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                                    setStatus("camera " + cameraId + " " + slot);
                                    Log.i(TAG, "dash camera preview started id=" + cameraId);
                                } catch (CameraAccessException | IllegalStateException e) {
                                    Log.i(TAG, "dash camera preview failed id=" + cameraId
                                            + ": " + shortError(e));
                                    setStatus("camera " + cameraId + " preview failed");
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                Log.i(TAG, "dash camera configure failed id=" + cameraId);
                                setStatus("camera " + cameraId + " configure failed");
                            }
                        }, cameraHandler);
            } catch (CameraAccessException | IllegalStateException e) {
                Log.i(TAG, "dash camera session failed id=" + cameraId + ": " + shortError(e));
                setStatus("camera " + cameraId + " session failed");
            }
        }

        private void closeCamera() {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        }

        private void setStatus(String message) {
            Log.i(TAG, "dash camera status=" + message);
            if (statusView != null) {
                statusView.post(() -> statusView.setText(message));
            }
        }

    }
}
