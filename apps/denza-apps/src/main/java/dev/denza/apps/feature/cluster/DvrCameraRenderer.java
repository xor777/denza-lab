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
    private static final float DENOISE_STRENGTH = 1.0f;
    private static final float LOWER_MID_DENOISE_STRENGTH = 0.65f;
    private static final float WIDE_DENOISE_STRENGTH = 0.55f;
    private static final float DENOISE_DETAIL_RETENTION = 0.25f;
    private static final float EDGE_THRESHOLD = 0.012f;
    private static final float HIGHLIGHT_START = 0.84f;
    private static final float LOCAL_CONTRAST_STRENGTH = 0.38f;
    private static final float UPPER_LOCAL_CONTRAST_STRENGTH = 0.20f;
    private static final float LOCAL_CONTRAST_RADIUS = 9.0f;
    private static final CaptureRequest.Key<Byte> BYD_MFNR_MODE =
            new CaptureRequest.Key<>(
                    "com.byd.camera.mfnr.mode",
                    Byte.class);

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
            int noiseReductionMode =
                    chooseNoiseReductionMode(cameraManager);
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
                            createPreviewSession(
                                    surfaceTexture,
                                    previewSize,
                                    noiseReductionMode);
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

    private int chooseNoiseReductionMode(CameraManager cameraManager)
            throws CameraAccessException {
        CameraCharacteristics characteristics =
                cameraManager.getCameraCharacteristics(CAMERA_ID);
        int[] availableModes = characteristics.get(
                CameraCharacteristics
                        .NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
        if (availableModes != null) {
            for (int mode : availableModes) {
                if (mode == CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) {
                    return mode;
                }
            }
        }
        return CaptureRequest.NOISE_REDUCTION_MODE_FAST;
    }

    private void createPreviewSession(
            SurfaceTexture surfaceTexture,
            Size previewSize,
            int noiseReductionMode) {
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
            request.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    noiseReductionMode);
            request.set(
                    CaptureRequest.EDGE_MODE,
                    CaptureRequest.EDGE_MODE_OFF);
            try {
                request.set(BYD_MFNR_MODE, (byte) 1);
            } catch (IllegalArgumentException error) {
                android.util.Log.w(
                        "DenzaDvrCamera",
                        "BYD MFNR request unsupported",
                        error);
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
                                        handler);
                                notifyReady(
                                        "camera2 id=" + CAMERA_ID
                                                + " preview="
                                                + previewSize.getWidth()
                                                + "x" + previewSize.getHeight()
                                                + " nr="
                                                + noiseReductionMode);
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
                        + "uniform float lowerMidDenoiseStrength;\n"
                        + "uniform float wideDenoiseStrength;\n"
                        + "uniform float denoiseDetailRetention;\n"
                        + "uniform float edgeThreshold;\n"
                        + "uniform float highlightStart;\n"
                        + "uniform float localContrastStrength;\n"
                        + "uniform float upperLocalContrastStrength;\n"
                        + "uniform float localContrastRadius;\n"
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
                        + "  half broad0 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(-localContrastRadius, 0.0)).rgb);\n"
                        + "  half broad1 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(localContrastRadius, 0.0)).rgb);\n"
                        + "  half broad2 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(0.0, -localContrastRadius)).rgb);\n"
                        + "  half broad3 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(0.0, localContrastRadius)).rgb);\n"
                        + "  half farRadius = half("
                        + "localContrastRadius * 2.0);\n"
                        + "  half diagonalRadius = half("
                        + "localContrastRadius * 1.4142);\n"
                        + "  half far0 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(-farRadius, 0.0)).rgb);\n"
                        + "  half far1 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(farRadius, 0.0)).rgb);\n"
                        + "  half far2 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(0.0, -farRadius)).rgb);\n"
                        + "  half far3 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(0.0, farRadius)).rgb);\n"
                        + "  half far4 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(-diagonalRadius, "
                        + "-diagonalRadius)).rgb);\n"
                        + "  half far5 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(diagonalRadius, "
                        + "-diagonalRadius)).rgb);\n"
                        + "  half far6 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(-diagonalRadius, "
                        + "diagonalRadius)).rgb);\n"
                        + "  half far7 = adaptiveLuma(cameraFrame.eval("
                        + "p + float2(diagonalRadius, "
                        + "diagonalRadius)).rgb);\n"
                        + "  half bw0 = 0.55 * edgeWeight(center, broad0);\n"
                        + "  half bw1 = 0.55 * edgeWeight(center, broad1);\n"
                        + "  half bw2 = 0.55 * edgeWeight(center, broad2);\n"
                        + "  half bw3 = 0.55 * edgeWeight(center, broad3);\n"
                        + "  half wideWeightSum = weightSum + bw0 + bw1 "
                        + "+ bw2 + bw3;\n"
                        + "  half wideMean = (weightSum * localMean "
                        + "+ bw0 * broad0 + bw1 * broad1 "
                        + "+ bw2 * broad2 + bw3 * broad3) "
                        + "/ wideWeightSum;\n"
                        + "  half crushMean = (2.0 * localMean + broad0 "
                        + "+ broad1 + broad2 + broad3 + 0.35 * (far0 "
                        + "+ far1 + far2 + far3 + far4 + far5 + far6 "
                        + "+ far7)) / 8.8;\n"
                        + "  half denoiseMean = mix(wideMean, crushMean, "
                        + "half(wideDenoiseStrength));\n"
                        + "  half delta = center - denoiseMean;\n"
                        + "  half cleanDetail = sign(delta) * max("
                        + "abs(delta) - half(edgeThreshold), 0.0);\n"
                        + "  half filtered = denoiseMean "
                        + "+ half(denoiseDetailRetention) * cleanDetail;\n"
                        + "  half cutoff = half(shadowCutoff);\n"
                        + "  half deepDenoiseMask = 1.0 - smoothstep("
                        + "0.12, 0.38, center);\n"
                        + "  half denoiseRange = 1.0 - smoothstep("
                        + "0.58, 0.72, center);\n"
                        + "  half denoiseAmount = mix("
                        + "half(lowerMidDenoiseStrength), "
                        + "half(denoiseStrength), deepDenoiseMask) "
                        + "* denoiseRange;\n"
                        + "  half base = mix(center, filtered, "
                        + "denoiseAmount);\n"
                        + "  half broadMean = (2.0 * localMean + broad0 "
                        + "+ broad1 + broad2 + broad3) / 6.0;\n"
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
                        + "  half midtoneMask = smoothstep(0.16, 0.30, mapped) "
                        + "* (1.0 - smoothstep(0.58, 0.74, mapped));\n"
                        + "  half upperMidtoneMask = smoothstep("
                        + "0.50, 0.62, mapped) "
                        + "* (1.0 - smoothstep(0.82, 0.90, mapped));\n"
                        + "  half localDetail = clamp("
                        + "base - broadMean, -0.10, 0.10);\n"
                        + "  half localGain = half(localContrastStrength) "
                        + "* midtoneMask + half(upperLocalContrastStrength) "
                        + "* upperMidtoneMask;\n"
                        + "  localGain *= 1.0 - 0.55 * denoiseAmount;\n"
                        + "  mapped += localDetail * localGain;\n"
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
        shader.setFloatUniform(
                "lowerMidDenoiseStrength",
                LOWER_MID_DENOISE_STRENGTH);
        shader.setFloatUniform(
                "wideDenoiseStrength",
                WIDE_DENOISE_STRENGTH);
        shader.setFloatUniform(
                "denoiseDetailRetention",
                DENOISE_DETAIL_RETENTION);
        shader.setFloatUniform("edgeThreshold", EDGE_THRESHOLD);
        shader.setFloatUniform("highlightStart", HIGHLIGHT_START);
        shader.setFloatUniform("localContrastStrength", LOCAL_CONTRAST_STRENGTH);
        shader.setFloatUniform(
                "upperLocalContrastStrength",
                UPPER_LOCAL_CONTRAST_STRENGTH);
        shader.setFloatUniform("localContrastRadius", LOCAL_CONTRAST_RADIUS);
        textureView.setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(shader, "cameraFrame"));
        android.util.Log.i(
                "DenzaDvrCamera",
                "smart monochrome profile applied"
                        + " shadow=" + SHADOW_STRENGTH
                        + "/" + SHADOW_CUTOFF
                        + " contrast=" + SHADOW_CONTRAST
                        + " denoise=" + DENOISE_STRENGTH
                        + "/" + LOWER_MID_DENOISE_STRENGTH
                        + "/" + WIDE_DENOISE_STRENGTH
                        + " detail=" + DENOISE_DETAIL_RETENTION
                        + " localContrast=" + LOCAL_CONTRAST_STRENGTH
                        + "/" + UPPER_LOCAL_CONTRAST_STRENGTH
                        + "@" + LOCAL_CONTRAST_RADIUS);
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
