package dev.denza.mirrors.probe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.util.Collections;

final class CameraStreamSource {
    interface Logger {
        void log(String message);
    }

    private static final String TAG = "DenzaCameraStream";

    private Context context;
    private String cameraId;
    private Surface outputSurface;
    private Logger logger;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private boolean closing;

    void start(Context context, String cameraId, Surface outputSurface, Logger logger) {
        this.context = context.getApplicationContext();
        this.cameraId = cameraId == null || cameraId.isEmpty() ? "0" : cameraId;
        this.outputSurface = outputSurface;
        this.logger = logger;
        if (outputSurface == null || !outputSurface.isValid()) {
            log("camera stream surface invalid");
            return;
        }
        if (this.context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            log("camera stream permission denied");
            return;
        }
        CameraManager cameraManager =
                (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            log("camera stream service missing");
            return;
        }
        startThread();
        try {
            log("camera stream opening id=" + this.cameraId);
            cameraManager.openCamera(this.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    log("camera stream opened id=" + CameraStreamSource.this.cameraId);
                    createSession(CameraDevice.TEMPLATE_RECORD);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    log("camera stream disconnected id=" + CameraStreamSource.this.cameraId);
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    log("camera stream error id=" + CameraStreamSource.this.cameraId
                            + " error=" + error);
                    camera.close();
                    cameraDevice = null;
                }
            }, cameraHandler);
        } catch (CameraAccessException | RuntimeException e) {
            log("camera stream open failed id=" + this.cameraId + ": " + shortError(e));
        }
    }

    void stop() {
        closing = true;
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            } catch (CameraAccessException | IllegalStateException e) {
                log("camera stream stop captures failed: " + shortError(e));
            }
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        stopThread();
        outputSurface = null;
        context = null;
        logger = null;
        closing = false;
    }

    private void startThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("DenzaCameraStream");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void createSession(int template) {
        CameraDevice camera = cameraDevice;
        Surface surface = outputSurface;
        if (camera == null || surface == null || !surface.isValid() || closing) {
            return;
        }
        try {
            CaptureRequest.Builder requestBuilder = camera.createCaptureRequest(template);
            requestBuilder.addTarget(surface);
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            camera.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (closing) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(requestBuilder.build(), null,
                                        cameraHandler);
                                log("camera stream started id=" + cameraId
                                        + " template=" + template);
                            } catch (CameraAccessException | IllegalStateException e) {
                                log("camera stream repeat failed id=" + cameraId
                                        + ": " + shortError(e));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            log("camera stream configure failed id=" + cameraId
                                    + " template=" + template);
                            if (template == CameraDevice.TEMPLATE_RECORD) {
                                createSession(CameraDevice.TEMPLATE_PREVIEW);
                            }
                        }
                    }, cameraHandler);
        } catch (CameraAccessException | RuntimeException e) {
            log("camera stream session failed id=" + cameraId
                    + " template=" + template + ": " + shortError(e));
            if (template == CameraDevice.TEMPLATE_RECORD) {
                createSession(CameraDevice.TEMPLATE_PREVIEW);
            }
        }
    }

    private void log(String message) {
        Log.i(TAG, message);
        if (logger != null) {
            logger.log(message);
        }
    }

    private static String shortError(Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + " " + message;
    }
}
