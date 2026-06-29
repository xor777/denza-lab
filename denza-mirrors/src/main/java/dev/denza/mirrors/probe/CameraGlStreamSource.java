package dev.denza.mirrors.probe;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class CameraGlStreamSource {
    interface Logger {
        void log(String message);
    }

    private static final String TAG = "DenzaCameraGlStream";
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final float[] FULL_QUAD = {
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
    };
    private static final float[] BASE_TEX = {
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
    };

    private Context context;
    private String cameraId;
    private Surface outputSurface;
    private int outputWidth;
    private int outputHeight;
    private int requestedCameraWidth;
    private int requestedCameraHeight;
    private int rotationDegrees;
    private boolean mirrorX;
    private boolean mirrorY;
    private float drawScaleX;
    private float drawScaleY;
    private Logger logger;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private SurfaceTexture surfaceTexture;
    private Surface cameraSurface;
    private Size cameraSize;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int textureId;
    private int program;
    private int aPosition;
    private int aTexCoord;
    private int uTexMatrix;
    private int frameCount;
    private boolean closing;
    private boolean framePending;
    private final float[] surfaceTextureMatrix = new float[16];
    private FloatBuffer vertexBuffer;
    private FloatBuffer texBuffer;

    void start(Context context, String cameraId, Surface outputSurface,
            int outputWidth, int outputHeight, int requestedCameraWidth,
            int requestedCameraHeight, int rotationDegrees, boolean mirrorX,
            boolean mirrorY, float drawScaleX, float drawScaleY, Logger logger) {
        this.context = context.getApplicationContext();
        this.cameraId = cameraId == null || cameraId.isEmpty() ? "0" : cameraId;
        this.outputSurface = outputSurface;
        this.outputWidth = Math.max(1, outputWidth);
        this.outputHeight = Math.max(1, outputHeight);
        this.requestedCameraWidth = Math.max(1, requestedCameraWidth);
        this.requestedCameraHeight = Math.max(1, requestedCameraHeight);
        this.rotationDegrees = normalizeRotation(rotationDegrees);
        this.mirrorX = mirrorX;
        this.mirrorY = mirrorY;
        this.drawScaleX = clampScale(drawScaleX);
        this.drawScaleY = clampScale(drawScaleY);
        this.logger = logger;
        if (outputSurface == null || !outputSurface.isValid()) {
            log("camera gl output surface invalid");
            return;
        }
        if (this.context.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            log("camera gl permission denied");
            return;
        }
        thread = new HandlerThread("DenzaCameraGlStream");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::startOnGlThread);
    }

    void stop() {
        closing = true;
        Handler localHandler = handler;
        if (localHandler != null) {
            CountDownLatch latch = new CountDownLatch(1);
            localHandler.post(() -> {
                try {
                    stopOnGlThread();
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(1200L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        handler = null;
        context = null;
        outputSurface = null;
        logger = null;
        closing = false;
    }

    private void startOnGlThread() {
        try {
            initEgl();
            initProgram();
            initTexture();
            cameraSize = chooseCameraSize();
            surfaceTexture.setDefaultBufferSize(cameraSize.getWidth(), cameraSize.getHeight());
            cameraSurface = new Surface(surfaceTexture);
            surfaceTexture.setOnFrameAvailableListener(texture -> {
                if (closing || handler == null) {
                    return;
                }
                if (framePending) {
                    return;
                }
                framePending = true;
                handler.post(this::renderFrame);
            }, handler);
            openCamera();
            log("camera gl initialized id=" + cameraId
                    + " camera=" + cameraSize.getWidth() + "x" + cameraSize.getHeight()
                    + " output=" + outputWidth + "x" + outputHeight
                    + " rotate=" + rotationDegrees
                    + " mirrorX=" + mirrorX + " mirrorY=" + mirrorY
                    + " scale=" + drawScaleX + "x" + drawScaleY);
        } catch (RuntimeException e) {
            log("camera gl init failed: " + shortError(e));
            stopOnGlThread();
        }
    }

    private void stopOnGlThread() {
        closeCamera();
        if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (textureId != 0) {
            int[] textures = {textureId};
            GLES20.glDeleteTextures(1, textures, 0);
            textureId = 0;
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
        framePending = false;
    }

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("eglGetDisplay failed");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new IllegalStateException("eglInitialize failed");
        }
        int[] configAttribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0,
                configs.length, numConfigs, 0) || numConfigs[0] <= 0) {
            throw new IllegalStateException("eglChooseConfig failed");
        }
        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("eglCreateContext failed");
        }
        int[] surfaceAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], outputSurface,
                surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("eglCreateWindowSurface failed");
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new IllegalStateException("eglMakeCurrent failed");
        }
    }

    private void initProgram() {
        String vertexShader =
                "attribute vec4 aPosition;\n"
                        + "attribute vec4 aTexCoord;\n"
                        + "uniform mat4 uTexMatrix;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "void main() {\n"
                        + "  gl_Position = aPosition;\n"
                        + "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n"
                        + "}\n";
        String fragmentShader =
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "uniform samplerExternalOES sTexture;\n"
                        + "void main() {\n"
                        + "  gl_FragColor = texture2D(sTexture, vTexCoord);\n"
                        + "}\n";
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexShader);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (linkStatus[0] == 0) {
            String info = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            program = 0;
            throw new IllegalStateException("program link failed " + info);
        }
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
        vertexBuffer = toFloatBuffer(buildVertexCoords());
        texBuffer = toFloatBuffer(buildTextureCoords());
    }

    private void initTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        surfaceTexture = new SurfaceTexture(textureId);
    }

    private Size chooseCameraSize() {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            return new Size(requestedCameraWidth, requestedCameraHeight);
        }
        try {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
            if (sizes == null || sizes.length == 0) {
                return new Size(requestedCameraWidth, requestedCameraHeight);
            }
            Size exact = findExactSize(sizes, requestedCameraWidth, requestedCameraHeight);
            if (exact != null) {
                return exact;
            }
            int swappedWidth = requestedCameraHeight;
            int swappedHeight = requestedCameraWidth;
            Size swapped = findExactSize(sizes, swappedWidth, swappedHeight);
            if (swapped != null && rotationDegrees % 180 != 0) {
                return swapped;
            }
            Size best = sizes[0];
            int bestScore = Integer.MAX_VALUE;
            float targetAspect = requestedCameraWidth / (float) requestedCameraHeight;
            for (Size size : sizes) {
                if (size.getWidth() > 1920 || size.getHeight() > 1920) {
                    continue;
                }
                float aspect = size.getWidth() / (float) size.getHeight();
                int score = Math.round(Math.abs(aspect - targetAspect) * 10000f)
                        + Math.abs(size.getWidth() - requestedCameraWidth)
                        + Math.abs(size.getHeight() - requestedCameraHeight);
                if (score < bestScore) {
                    bestScore = score;
                    best = size;
                }
            }
            return best;
        } catch (CameraAccessException | RuntimeException e) {
            log("camera gl choose size failed id=" + cameraId + ": " + shortError(e));
            return new Size(requestedCameraWidth, requestedCameraHeight);
        }
    }

    private Size findExactSize(Size[] sizes, int width, int height) {
        for (Size size : sizes) {
            if (size.getWidth() == width && size.getHeight() == height) {
                return size;
            }
        }
        return null;
    }

    private void openCamera() {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            log("camera gl service missing");
            return;
        }
        try {
            log("camera gl opening id=" + cameraId);
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    log("camera gl opened id=" + cameraId);
                    createCameraSession(CameraDevice.TEMPLATE_PREVIEW);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    log("camera gl disconnected id=" + cameraId);
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    log("camera gl error id=" + cameraId + " error=" + error);
                    camera.close();
                    cameraDevice = null;
                }
            }, handler);
        } catch (CameraAccessException | RuntimeException e) {
            log("camera gl open failed id=" + cameraId + ": " + shortError(e));
        }
    }

    private void createCameraSession(int template) {
        CameraDevice camera = cameraDevice;
        if (camera == null || cameraSurface == null || !cameraSurface.isValid()) {
            return;
        }
        try {
            CaptureRequest.Builder requestBuilder = camera.createCaptureRequest(template);
            requestBuilder.addTarget(cameraSurface);
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            camera.createCaptureSession(Collections.singletonList(cameraSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (closing) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(requestBuilder.build(), null, handler);
                                log("camera gl started id=" + cameraId
                                        + " template=" + template);
                            } catch (CameraAccessException | IllegalStateException e) {
                                log("camera gl repeat failed id=" + cameraId
                                        + ": " + shortError(e));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            log("camera gl configure failed id=" + cameraId
                                    + " template=" + template);
                        }
                    }, handler);
        } catch (CameraAccessException | RuntimeException e) {
            log("camera gl session failed id=" + cameraId
                    + " template=" + template + ": " + shortError(e));
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            } catch (CameraAccessException | IllegalStateException e) {
                log("camera gl stop captures failed: " + shortError(e));
            }
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void renderFrame() {
        framePending = false;
        if (closing || surfaceTexture == null || eglDisplay == EGL14.EGL_NO_DISPLAY) {
            return;
        }
        try {
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(surfaceTextureMatrix);
            GLES20.glViewport(0, 0, outputWidth, outputHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, surfaceTextureMatrix, 0);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0,
                    vertexBuffer);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0,
                    texBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTexCoord);
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime());
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            frameCount++;
            if (frameCount % 60 == 0) {
                log("camera gl frame=" + frameCount
                        + " id=" + cameraId
                        + " rotate=" + rotationDegrees);
            }
        } catch (RuntimeException e) {
            log("camera gl render failed: " + shortError(e));
        }
    }

    private float[] buildTextureCoords() {
        float[] coords = new float[BASE_TEX.length];
        double radians = Math.toRadians(rotationDegrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        for (int i = 0; i < BASE_TEX.length; i += 2) {
            float x = BASE_TEX[i] - 0.5f;
            float y = BASE_TEX[i + 1] - 0.5f;
            if (mirrorX) {
                x = -x;
            }
            if (mirrorY) {
                y = -y;
            }
            float rotatedX = x * cos - y * sin;
            float rotatedY = x * sin + y * cos;
            coords[i] = rotatedX + 0.5f;
            coords[i + 1] = rotatedY + 0.5f;
        }
        return coords;
    }

    private float[] buildVertexCoords() {
        float scaleX = drawScaleX;
        float scaleY = drawScaleY;
        return new float[]{
                -scaleX, -scaleY,
                 scaleX, -scaleY,
                -scaleX,  scaleY,
                 scaleX,  scaleY
        };
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            String info = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed " + info);
        }
        return shader;
    }

    private static FloatBuffer toFloatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values);
        buffer.position(0);
        return buffer;
    }

    private static int normalizeRotation(int rotation) {
        int normalized = rotation % 360;
        if (normalized < 0) {
            normalized += 360;
        }
        if (normalized == 90 || normalized == 180 || normalized == 270) {
            return normalized;
        }
        return 0;
    }

    private static float clampScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) {
            return 1f;
        }
        return Math.max(0.1f, Math.min(3f, scale));
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
