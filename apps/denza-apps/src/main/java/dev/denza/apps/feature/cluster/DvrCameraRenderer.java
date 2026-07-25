package dev.denza.apps.feature.cluster;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import java.util.Collections;

/** Camera2 renderer for the verified BYD DVR source (camera id 0). */
public final class DvrCameraRenderer implements TextureView.SurfaceTextureListener {
    public interface Listener {
        void onReady(String details);
        void onFailure(String details);
    }

    private static final String CAMERA_ID = "0";
    private static final float SHADOW_STRENGTH = 0.78f;
    private static final float SHADOW_CUTOFF = 0.48f;
    private static final float SHADOW_GAMMA = 0.56f;
    private static final float SHADOW_CONTRAST = 0.55f;
    private static final float DENOISE_STRENGTH = 0.42f;
    private static final float EDGE_THRESHOLD = 0.004f;
    private static final float HIGHLIGHT_START = 0.72f;

    private final Context context;
    private final TextureView textureView;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Surface previewSurface;
    private boolean opening;
    private boolean firstFrameReported;
    private volatile boolean stopping = true;

    public DvrCameraRenderer(Context context, TextureView textureView, Listener listener) {
        this.context = context;
        this.textureView = textureView;
        this.listener = listener;
    }

    public void start() {
        stop();
        stopping = false;
        opening = false;
        firstFrameReported = false;
        try {
            applySmartMonochromeProcessing();
        } catch (IllegalArgumentException error) {
            android.util.Log.e(
                    "DenzaDvrCamera",
                    "smart monochrome shader failed",
                    error);
            notifyFailure("monochrome shader " + shortError(error));
            return;
        }
        startCameraThread();
        textureView.setSurfaceTextureListener(this);
        SurfaceTexture texture = textureView.getSurfaceTexture();
        if (textureView.isAvailable() && texture != null) {
            onSurfaceTextureAvailable(
                    texture,
                    Math.max(1, textureView.getWidth()),
                    Math.max(1, textureView.getHeight()));
        }
    }

    public void stop() {
        stopping = true;
        opening = false;
        clearTextureView();
        closeCamera();
        stopCameraThread();
    }

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
            android.util.Log.i("DenzaDvrCamera", "first frame received");
        }
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("DenzaDvrCamera");
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
        if (stopping || opening || cameraDevice != null) {
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            notifyFailure("camera permission denied");
            return;
        }
        CameraManager cameraManager =
                (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            notifyFailure("camera service missing");
            return;
        }
        try {
            Size previewSize = choosePreviewSize(cameraManager);
            surfaceTexture.setDefaultBufferSize(
                    previewSize.getWidth(),
                    previewSize.getHeight());
            opening = true;
            cameraManager.openCamera(
                    CAMERA_ID,
                    new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(CameraDevice camera) {
                            opening = false;
                            if (stopping) {
                                camera.close();
                                return;
                            }
                            cameraDevice = camera;
                            createPreviewSession(surfaceTexture, previewSize);
                        }

                        @Override
                        public void onDisconnected(CameraDevice camera) {
                            opening = false;
                            camera.close();
                            cameraDevice = null;
                            if (!stopping) {
                                notifyFailure("camera disconnected");
                            }
                        }

                        @Override
                        public void onError(CameraDevice camera, int error) {
                            opening = false;
                            camera.close();
                            cameraDevice = null;
                            if (!stopping) {
                                notifyFailure("camera error " + error);
                            }
                        }
                    },
                    cameraHandler);
        } catch (CameraAccessException | IllegalArgumentException
                | SecurityException error) {
            opening = false;
            notifyFailure("camera open " + shortError(error));
        }
    }

    private Size choosePreviewSize(CameraManager cameraManager)
            throws CameraAccessException {
        CameraCharacteristics characteristics =
                cameraManager.getCameraCharacteristics(CAMERA_ID);
        StreamConfigurationMap map = characteristics.get(
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

    private void createPreviewSession(
            SurfaceTexture surfaceTexture,
            Size previewSize) {
        CameraDevice camera = cameraDevice;
        Handler handler = cameraHandler;
        if (camera == null || handler == null || stopping) {
            return;
        }
        previewSurface = new Surface(surfaceTexture);
        try {
            CaptureRequest.Builder request =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
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
                                        handler);
                                notifyReady(
                                        "camera2 id=" + CAMERA_ID
                                                + " preview="
                                                + previewSize.getWidth()
                                                + "x"
                                                + previewSize.getHeight());
                            } catch (CameraAccessException
                                    | IllegalStateException error) {
                                notifyFailure("preview start " + shortError(error));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            session.close();
                            if (!stopping) {
                                notifyFailure("preview configure failed");
                            }
                        }
                    },
                    handler);
        } catch (CameraAccessException | IllegalStateException error) {
            notifyFailure("preview session " + shortError(error));
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

    private void applySmartMonochromeProcessing() {
        textureView.setLayerType(View.LAYER_TYPE_NONE, null);
        RuntimeShader shader = new RuntimeShader(
                "uniform shader cameraFrame;\n"
                        + "uniform float shadowStrength;\n"
                        + "uniform float shadowCutoff;\n"
                        + "uniform float shadowGamma;\n"
                        + "uniform float shadowContrast;\n"
                        + "uniform float denoiseStrength;\n"
                        + "uniform float edgeThreshold;\n"
                        + "uniform float highlightStart;\n"
                        + "half adaptiveLuma(half3 rgb) {\n"
                        + "  half perceptual = dot(rgb, "
                        + "half3(0.2627, 0.6780, 0.0593));\n"
                        + "  half greenDetail = dot(rgb, "
                        + "half3(0.18, 0.76, 0.06));\n"
                        + "  half channelMax = max(rgb.r, max(rgb.g, rgb.b));\n"
                        + "  half channelMin = min(rgb.r, min(rgb.g, rgb.b));\n"
                        + "  half chromaSpan = channelMax - channelMin;\n"
                        + "  half dark = 1.0 - smoothstep("
                        + "0.10, 0.38, perceptual);\n"
                        + "  half neutral = 1.0 - smoothstep("
                        + "0.06, 0.30, chromaSpan);\n"
                        + "  return mix(perceptual, greenDetail, "
                        + "0.55 * dark * neutral);\n"
                        + "}\n"
                        + "half edgeWeight(half center, half neighbor) {\n"
                        + "  return 1.0 / (1.0 + "
                        + "42.0 * abs(neighbor - center));\n"
                        + "}\n"
                        + "half4 main(float2 p) {\n"
                        + "  half4 src = cameraFrame.eval(p);\n"
                        + "  half center = adaptiveLuma(src.rgb);\n"
                        + "  half n0 = adaptiveLuma("
                        + "cameraFrame.eval(p + float2(-1.5, 0.0)).rgb);\n"
                        + "  half n1 = adaptiveLuma("
                        + "cameraFrame.eval(p + float2(1.5, 0.0)).rgb);\n"
                        + "  half n2 = adaptiveLuma("
                        + "cameraFrame.eval(p + float2(0.0, -1.5)).rgb);\n"
                        + "  half n3 = adaptiveLuma("
                        + "cameraFrame.eval(p + float2(0.0, 1.5)).rgb);\n"
                        + "  half n4 = adaptiveLuma("
                        + "cameraFrame.eval(p + float2(-2.5, -2.5)).rgb);\n"
                        + "  half n5 = adaptiveLuma("
                        + "cameraFrame.eval(p + float2(2.5, -2.5)).rgb);\n"
                        + "  half n6 = adaptiveLuma("
                        + "cameraFrame.eval(p + float2(-2.5, 2.5)).rgb);\n"
                        + "  half n7 = adaptiveLuma("
                        + "cameraFrame.eval(p + float2(2.5, 2.5)).rgb);\n"
                        + "  half w0 = edgeWeight(center, n0);\n"
                        + "  half w1 = edgeWeight(center, n1);\n"
                        + "  half w2 = edgeWeight(center, n2);\n"
                        + "  half w3 = edgeWeight(center, n3);\n"
                        + "  half w4 = 0.70 * edgeWeight(center, n4);\n"
                        + "  half w5 = 0.70 * edgeWeight(center, n5);\n"
                        + "  half w6 = 0.70 * edgeWeight(center, n6);\n"
                        + "  half w7 = 0.70 * edgeWeight(center, n7);\n"
                        + "  half weightSum = 2.4 + w0 + w1 + w2 + w3 "
                        + "+ w4 + w5 + w6 + w7;\n"
                        + "  half localMean = (2.4 * center "
                        + "+ w0 * n0 + w1 * n1 + w2 * n2 + w3 * n3 "
                        + "+ w4 * n4 + w5 * n5 + w6 * n6 + w7 * n7) "
                        + "/ weightSum;\n"
                        + "  half delta = center - localMean;\n"
                        + "  half cleanDetail = sign(delta) * max("
                        + "abs(delta) - half(edgeThreshold), 0.0);\n"
                        + "  half filtered = localMean + 0.92 * cleanDetail;\n"
                        + "  half cutoff = half(shadowCutoff);\n"
                        + "  half denoiseMask = 1.0 - smoothstep("
                        + "0.16, cutoff, center);\n"
                        + "  half base = mix(center, filtered, "
                        + "half(denoiseStrength) * denoiseMask);\n"
                        + "  half shadowMask = 1.0 - smoothstep("
                        + "cutoff * 0.48, cutoff, base);\n"
                        + "  half blackOffset = 0.050 * (1.0 - base);\n"
                        + "  half target = max(0.0, pow("
                        + "max(base, 0.003), half(shadowGamma)) "
                        + "- blackOffset);\n"
                        + "  half mapped = mix(base, target, "
                        + "shadowMask * half(shadowStrength));\n"
                        + "  half contrastMask = 1.0 - smoothstep("
                        + "0.36, 0.66, mapped);\n"
                        + "  mapped += (mapped - 0.16) "
                        + "* half(shadowContrast) * contrastMask;\n"
                        + "  half shoulder = half(highlightStart);\n"
                        + "  half highlightT = clamp("
                        + "(mapped - shoulder) / max(1.0 - shoulder, 0.001), "
                        + "0.0, 1.0);\n"
                        + "  half rolled = shoulder + (1.0 - shoulder) "
                        + "* highlightT / (1.0 + 0.28 * highlightT);\n"
                        + "  mapped = mix(mapped, rolled, smoothstep("
                        + "shoulder, 0.94, mapped));\n"
                        + "  return half4(half3(clamp(mapped, 0.0, 1.0)), "
                        + "src.a);\n"
                        + "}\n");
        shader.setFloatUniform("shadowStrength", SHADOW_STRENGTH);
        shader.setFloatUniform("shadowCutoff", SHADOW_CUTOFF);
        shader.setFloatUniform("shadowGamma", SHADOW_GAMMA);
        shader.setFloatUniform("shadowContrast", SHADOW_CONTRAST);
        shader.setFloatUniform("denoiseStrength", DENOISE_STRENGTH);
        shader.setFloatUniform("edgeThreshold", EDGE_THRESHOLD);
        shader.setFloatUniform("highlightStart", HIGHLIGHT_START);
        textureView.setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "cameraFrame"));
        android.util.Log.i(
                "DenzaDvrCamera",
                "smart monochrome profile applied"
                        + " shadow=" + SHADOW_STRENGTH
                        + "/" + SHADOW_CUTOFF
                        + " contrast=" + SHADOW_CONTRAST
                        + " denoise=" + DENOISE_STRENGTH);
    }

    private void clearTextureView() {
        Runnable clear = () -> {
            textureView.setSurfaceTextureListener(null);
            textureView.setRenderEffect(null);
            textureView.setLayerType(View.LAYER_TYPE_NONE, null);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clear.run();
        } else {
            mainHandler.post(clear);
        }
    }

    private void notifyReady(String details) {
        mainHandler.post(() -> {
            if (!stopping) {
                listener.onReady(details);
            }
        });
    }

    private void notifyFailure(String details) {
        mainHandler.post(() -> {
            if (!stopping) {
                listener.onFailure(details);
            }
        });
    }

    private static String shortError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : " " + message);
    }
}
