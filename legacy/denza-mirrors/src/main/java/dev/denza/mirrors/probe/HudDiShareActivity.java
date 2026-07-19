package dev.denza.mirrors.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class HudDiShareActivity extends Activity {
    private static final String TAG = "DenzaHudDiShare";
    private static final String API_ACTION = "com.byd.dishare.api.DiShareApiService";
    private static final String API_PACKAGE = "com.byd.dishare";
    private static final String API_DESCRIPTOR = "com.byd.dishare.api.IDiShareApiService";
    private static final String API_CLIENT_DESCRIPTOR = "com.byd.dishare.api.IDiShareApiClient";
    private static final String MIRROR_SOURCE_DESCRIPTOR =
            "com.byd.dishare.api.IMirrorSourceClient";

    private static final int TX_CREATE_CLIENT = 1;
    private static final int TX_REMOVE_CLIENT = 7;
    private static final int TX_QUICK_SHARE = 8;
    private static final int TX_SET_GESTURE_SHARE = 9;
    private static final int TX_FINISH_SHARE = 10;
    private static final int TX_SET_VIDEO_SIZE = 11;
    private static final int TX_SET_VIDEO_BOUNDS = 12;
    private static final int TX_SET_MIRROR_SOURCE_CLIENT = 16;
    public static final String EXTRA_FINISH = "finish";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static HudDiShareActivity activeInstance;
    private final ApiClientBinder apiClient = new ApiClientBinder();
    private final MirrorSourceClientBinder mirrorSourceClient = new MirrorSourceClientBinder();
    private final BydMediaStreamServer mediaStreamServer = new BydMediaStreamServer();
    private final CameraStreamSource cameraStreamSource = new CameraStreamSource();
    private final CameraGlStreamSource cameraGlStreamSource = new CameraGlStreamSource();

    private File statusFile;
    private TextView statusView;
    private IBinder apiBinder;
    private AvcSurfaceClient avcSurfaceClient;
    private boolean bound;
    private boolean registered;
    private boolean mediaStreamEnabled;
    private boolean patternEnabled;
    private boolean cameraStreamEnabled;
    private boolean cameraGlStreamEnabled;
    private boolean textureBridgeEnabled;
    private int streamWidth;
    private int streamHeight;
    private int textureWidth;
    private int textureHeight;
    private int viewpoint;
    private String cameraId;
    private Rect videoBounds;
    private Surface mediaInputSurface;
    private Surface cameraTextureSurface;
    private boolean ownsCameraTextureSurface;
    private SurfaceView cameraSurfaceView;
    private TextureView cameraTextureView;
    private Bitmap cameraCopyBitmap;
    private boolean pixelCopyPending;
    private int textureUpdateCount;
    private int patternFrame;
    private int bridgeFrame;
    private String lastStatus = "";
    private final Runnable patternRunnable = new Runnable() {
        @Override
        public void run() {
            drawPatternFrame();
            handler.postDelayed(this, 100L);
        }
    };
    private final Runnable textureCopyRunnable = new Runnable() {
        @Override
        public void run() {
            copyTextureFrame();
            handler.postDelayed(this, getIntent().getLongExtra("copy_interval_ms", 100L));
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            apiBinder = service;
            log("connected " + name.flattenToShortString());
            registerSource();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            apiBinder = null;
            bound = false;
            log("disconnected " + name.flattenToShortString());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getBooleanExtra(EXTRA_FINISH, false)) {
            finishActiveInstance();
            finishAndRemoveTask();
            return;
        }
        activeInstance = this;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        statusFile = new File(getFilesDir(), "dishare_api_status.txt");
        resetStatus();
        readMediaStreamConfig();
        if (shouldHideHostWindow()) {
            configureHostWindow();
        }
        if (getIntent().getBooleanExtra("debug_ui", false)) {
            showStatusView();
        } else if (mediaStreamEnabled && textureBridgeEnabled) {
            if (getIntent().getBooleanExtra("surface_view_bridge", true)) {
                showSurfaceViewBridge();
            } else {
                showTextureBridgeView();
            }
        }
        bindApi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_FINISH, false)) {
            finishFast();
            return;
        }
        setIntent(intent);
        finishFast();
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterSource();
        stopLocalMediaStream();
        if (bound) {
            try {
                unbindService(connection);
            } catch (RuntimeException e) {
                log("unbind failed: " + shortError(e));
            }
            bound = false;
        }
        if (activeInstance == this) {
            activeInstance = null;
        }
        super.onDestroy();
    }

    public static boolean finishActiveInstance() {
        HudDiShareActivity activity = activeInstance;
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::finishFast);
        return true;
    }

    private void finishFast() {
        handler.removeCallbacksAndMessages(null);
        unregisterSource();
        stopLocalMediaStream();
        finishAndRemoveTask();
    }

    private boolean shouldHideHostWindow() {
        return !getIntent().getBooleanExtra("debug_ui", false)
                && mediaStreamEnabled
                && !textureBridgeEnabled;
    }

    private void configureHostWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = 1;
        params.height = 1;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = -1000;
        params.y = -1000;
        params.alpha = 0.0f;
        window.setAttributes(params);
    }

    private void showStatusView() {
        statusView = new TextView(this);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(0xffffffff);
        statusView.setTextSize(28f);
        statusView.setBackgroundColor(0xff102030);
        statusView.setText("DiShare HUD source registering...");
        setContentView(statusView);
    }

    private void showTextureBridgeView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        cameraTextureView = new TextureView(this);
        cameraTextureView.setAlpha(getIntent().getFloatExtra("texture_alpha", 0.03f));
        cameraTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                surfaceTexture.setDefaultBufferSize(textureWidth, textureHeight);
                cameraTextureSurface = new Surface(surfaceTexture);
                ownsCameraTextureSurface = true;
                startAvcIntoTexture();
                handler.post(textureCopyRunnable);
                log("texture bridge available view=" + width + "x" + height
                        + " buffer=" + textureWidth + "x" + textureHeight);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture,
                    int width, int height) {
                surfaceTexture.setDefaultBufferSize(textureWidth, textureHeight);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                handler.removeCallbacks(textureCopyRunnable);
                releaseAvcTextureSurface();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                textureUpdateCount++;
            }
        });
        root.addView(cameraTextureView, new FrameLayout.LayoutParams(
                Math.max(1, textureWidth),
                Math.max(1, textureHeight),
                Gravity.TOP | Gravity.START));
        setContentView(root);
    }

    private void showSurfaceViewBridge() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        cameraSurfaceView = new SurfaceView(this);
        cameraSurfaceView.setZOrderOnTop(false);
        cameraSurfaceView.setAlpha(getIntent().getFloatExtra("surface_alpha", 0.08f));
        cameraSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                cameraTextureSurface = holder.getSurface();
                ownsCameraTextureSurface = false;
                startAvcIntoTexture();
                handler.post(textureCopyRunnable);
                log("surface bridge created valid="
                        + (cameraTextureSurface != null && cameraTextureSurface.isValid()));
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                cameraTextureSurface = holder.getSurface();
                ownsCameraTextureSurface = false;
                log("surface bridge changed " + width + "x" + height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                handler.removeCallbacks(textureCopyRunnable);
                if (avcSurfaceClient != null) {
                    avcSurfaceClient.release();
                    avcSurfaceClient = null;
                }
                cameraTextureSurface = null;
                ownsCameraTextureSurface = false;
                log("surface bridge destroyed");
            }
        });
        root.addView(cameraSurfaceView, new FrameLayout.LayoutParams(
                Math.max(1, textureWidth),
                Math.max(1, textureHeight),
                Gravity.TOP | Gravity.START));
        setContentView(root);
    }

    private void bindApi() {
        log("bind api");
        Intent intent = new Intent();
        intent.setAction(API_ACTION);
        intent.setPackage(API_PACKAGE);
        try {
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException e) {
            log("bind failed: " + shortError(e));
            return;
        }
        log("bind result=" + bound);
    }

    private void registerSource() {
        try {
            callCreateClient();
            registered = true;
            callSetVideoSize(streamWidth, streamHeight);
            callSetVideoBounds(videoBounds);
            callSetGestureShare(true);
            if (mediaStreamEnabled) {
                initializeLocalMediaStream();
            }
            boolean mirrorSource = getIntent().getBooleanExtra("mirror_source", mediaStreamEnabled);
            if (mirrorSource) {
                callSetMirrorSourceClient();
            } else {
                log("skip mirror source client");
            }
            log("source registered for " + clientPackageName());

            if (getIntent().getBooleanExtra("show_image", !mediaStreamEnabled)) {
                handler.postDelayed(this::launchImage, 400);
            }
            if (getIntent().getBooleanExtra("start_control", true)) {
                handler.postDelayed(this::startControlHudShare,
                        getIntent().getLongExtra("start_delay_ms", 1100L));
            }
            if (getIntent().getBooleanExtra("quick_share", false)) {
                handler.postDelayed(() -> callQuickShare(
                        getIntent().getStringExtra("target") == null
                                ? "fse" : getIntent().getStringExtra("target")),
                        getIntent().getLongExtra("quick_delay_ms", 1400L));
            }
            long autoStopMs = getIntent().getLongExtra("auto_stop_ms", 0L);
            if (autoStopMs > 0L) {
                handler.postDelayed(this::stopControlShare, autoStopMs);
                log("auto stop scheduled " + autoStopMs + "ms");
            }
        } catch (RuntimeException e) {
            log("register failed: " + shortError(e));
            Log.i(TAG, "register failed", e);
        }
    }

    private void unregisterSource() {
        if (!registered || apiBinder == null) {
            return;
        }
        try {
            callFinishShare();
        } catch (RuntimeException e) {
            log("finish failed: " + shortError(e));
        }
        try {
            callRemoveClient();
        } catch (RuntimeException e) {
            log("remove failed: " + shortError(e));
        }
        registered = false;
    }

    private void launchImage() {
        log("launch image");
        Intent intent = new Intent(this, HudImageActivity.class);
        intent.putExtra("label", "OUR APK DISHARE SOURCE");
        intent.putExtra("duration_ms", getIntent().getLongExtra("image_duration_ms", 0L));
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void startControlHudShare() {
        log("start control hud share");
        Intent intent = new Intent();
        intent.putExtra("command", "start_hud");
        intent.putExtra("app", controlAppName());
        intent.putExtra("package", controlPackageName());
        intent.putExtra("receiver", getIntent().getStringExtra("receiver") == null
                ? "screen_hud" : getIntent().getStringExtra("receiver"));
        intent.putExtra("provider", getIntent().getStringExtra("provider") == null
                ? "screen_ivi" : getIntent().getStringExtra("provider"));
        DiShareProbeReceiver.runProbe(this, intent, () -> log("control hud share finished"));
    }

    private void stopControlShare() {
        log("stop control share");
        Intent intent = new Intent();
        intent.putExtra("command", "stop");
        DiShareProbeReceiver.runProbe(this, intent, () -> log("control stop finished"));
        stopLocalMediaStream();
    }

    private void readMediaStreamConfig() {
        Intent intent = getIntent();
        mediaStreamEnabled = intent.getBooleanExtra("media_stream", false);
        patternEnabled = intent.getBooleanExtra("pattern", false);
        cameraStreamEnabled = intent.getBooleanExtra("camera_stream", false);
        cameraGlStreamEnabled = intent.getBooleanExtra("camera_gl_stream", false);
        textureBridgeEnabled = intent.getBooleanExtra("texture_bridge",
                !patternEnabled && !cameraStreamEnabled);
        streamWidth = Math.max(320, intent.getIntExtra("stream_width", 1280));
        streamHeight = Math.max(180, intent.getIntExtra("stream_height", 720));
        textureWidth = Math.max(320, intent.getIntExtra("texture_width", 1280));
        textureHeight = Math.max(180, intent.getIntExtra("texture_height", 720));
        viewpoint = intent.getIntExtra("viewpoint", defaultViewpoint());
        cameraId = intent.getStringExtra("camera_id");
        if (cameraId == null || cameraId.isEmpty()) {
            cameraId = "0";
        }
        videoBounds = buildVideoBounds(intent);
        log("config mediaStream=" + mediaStreamEnabled
                + " pattern=" + patternEnabled
                + " cameraStream=" + cameraStreamEnabled
                + " cameraGlStream=" + cameraGlStreamEnabled
                + " cameraId=" + cameraId
                + " textureBridge=" + textureBridgeEnabled
                + " stream=" + streamWidth + "x" + streamHeight
                + " texture=" + textureWidth + "x" + textureHeight
                + " viewpoint=" + viewpoint
                + " bounds=" + videoBounds);
    }

    private int defaultViewpoint() {
        String slot = getIntent().getStringExtra("slot");
        if ("right".equals(slot)) {
            return 3205;
        }
        return 2002;
    }

    private Rect buildVideoBounds(Intent intent) {
        if (intent.hasExtra("bounds_right")) {
            return new Rect(
                    intent.getIntExtra("bounds_left", 0),
                    intent.getIntExtra("bounds_top", 0),
                    intent.getIntExtra("bounds_right", 2560),
                    intent.getIntExtra("bounds_bottom", 720));
        }
        int screenWidth = Math.max(1, intent.getIntExtra("screen_width", 2560));
        int screenHeight = Math.max(1, intent.getIntExtra("screen_height", 720));
        int extendPercent = intent.getIntExtra("center_extend_percent", 20);
        int width = Math.max(1, screenWidth / 3
                + Math.round((screenWidth / 3f) * (extendPercent / 100f)));
        String slot = intent.getStringExtra("slot");
        int left;
        if ("right".equals(slot)) {
            left = screenWidth - width;
        } else if ("center".equals(slot)) {
            left = (screenWidth - width) / 2;
        } else {
            left = 0;
        }
        return new Rect(left, 0, Math.min(screenWidth, left + width), screenHeight);
    }

    private void initializeLocalMediaStream() {
        try {
            mediaInputSurface = mediaStreamServer.initialize(this, streamWidth, streamHeight);
            if (patternEnabled) {
                handler.post(patternRunnable);
            } else if (cameraStreamEnabled) {
                if (cameraGlStreamEnabled) {
                    cameraGlStreamSource.start(this, cameraId, mediaInputSurface,
                            streamWidth, streamHeight,
                            getIntent().getIntExtra("camera_width", streamWidth),
                            getIntent().getIntExtra("camera_height", streamHeight),
                            getIntent().getIntExtra("camera_rotate_degrees", 0),
                            getIntent().getBooleanExtra("camera_mirror_x", false),
                            getIntent().getBooleanExtra("camera_mirror_y", false),
                            getIntent().getFloatExtra("camera_draw_scale_x", 1f),
                            getIntent().getFloatExtra("camera_draw_scale_y", 1f),
                            this::log);
                } else {
                    cameraStreamSource.start(this, cameraId, mediaInputSurface, this::log);
                }
            } else if (textureBridgeEnabled) {
                if (cameraTextureSurface != null) {
                    startAvcIntoTexture();
                    handler.post(textureCopyRunnable);
                } else {
                    log("texture bridge waiting for surface");
                }
            } else {
                avcSurfaceClient = new AvcSurfaceClient(this, viewpoint,
                        getIntent().getBooleanExtra("uturn", false), this::log);
                avcSurfaceClient.start(mediaInputSurface);
            }
            log("local media stream ready surfaceValid="
                    + (mediaInputSurface != null && mediaInputSurface.isValid()));
        } catch (Exception e) {
            throw new IllegalStateException("media stream init failed", e);
        }
    }

    private void stopLocalMediaStream() {
        handler.removeCallbacks(patternRunnable);
        handler.removeCallbacks(textureCopyRunnable);
        if (avcSurfaceClient != null) {
            avcSurfaceClient.release();
            avcSurfaceClient = null;
        }
        cameraStreamSource.stop();
        cameraGlStreamSource.stop();
        releaseAvcTextureSurface();
        if (cameraCopyBitmap != null) {
            cameraCopyBitmap.recycle();
            cameraCopyBitmap = null;
        }
        pixelCopyPending = false;
        mediaInputSurface = null;
        mediaStreamServer.stop();
        mediaStreamServer.release();
    }

    private void startAvcIntoTexture() {
        if (avcSurfaceClient != null || cameraTextureSurface == null) {
            return;
        }
        avcSurfaceClient = new AvcSurfaceClient(this, viewpoint,
                getIntent().getBooleanExtra("uturn", false), this::log);
        avcSurfaceClient.start(cameraTextureSurface);
    }

    private void releaseAvcTextureSurface() {
        if (cameraTextureSurface != null) {
            if (ownsCameraTextureSurface) {
                cameraTextureSurface.release();
            }
            cameraTextureSurface = null;
        }
        ownsCameraTextureSurface = false;
    }

    private void copyTextureFrame() {
        TextureView textureView = cameraTextureView;
        Surface surface = mediaInputSurface;
        if (surface == null || !surface.isValid()) {
            return;
        }
        if (cameraSurfaceView != null) {
            requestSurfacePixelCopy();
            return;
        }
        Bitmap frame = null;
        if (textureView != null && textureView.isAvailable()) {
            ensureCameraCopyBitmap();
            try {
                frame = textureView.getBitmap(cameraCopyBitmap);
            } catch (RuntimeException e) {
                log("texture getBitmap failed: " + shortError(e));
            }
        }
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            if (frame == null) {
                drawSurfaceBridgeOverlay(canvas);
            } else {
                drawCameraFrame(canvas, frame);
            }
            bridgeFrame++;
        } catch (RuntimeException e) {
            log("texture copy failed: " + shortError(e));
            handler.removeCallbacks(textureCopyRunnable);
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (RuntimeException e) {
                    log("texture copy post failed: " + shortError(e));
                }
            }
        }
    }

    private void requestSurfacePixelCopy() {
        SurfaceView surfaceView = cameraSurfaceView;
        if (surfaceView == null || pixelCopyPending) {
            return;
        }
        Surface sourceSurface = surfaceView.getHolder().getSurface();
        if (sourceSurface == null || !sourceSurface.isValid()) {
            drawNoFrameToMediaSurface("NO SURFACEVIEW SURFACE frame=" + bridgeFrame);
            return;
        }
        ensureCameraCopyBitmap();
        pixelCopyPending = true;
        try {
            PixelCopy.request(surfaceView, cameraCopyBitmap, result -> {
                pixelCopyPending = false;
                if (result == PixelCopy.SUCCESS) {
                    drawBitmapToMediaSurface(cameraCopyBitmap);
                } else {
                    drawNoFrameToMediaSurface("PIXELCOPY result=" + result
                            + " frame=" + bridgeFrame);
                    if (bridgeFrame % 20 == 0) {
                        log("surface pixelcopy result=" + result
                                + " frame=" + bridgeFrame);
                    }
                }
            }, handler);
        } catch (IllegalArgumentException e) {
            pixelCopyPending = false;
            log("surface pixelcopy failed: " + shortError(e));
            drawNoFrameToMediaSurface("PIXELCOPY FAILED frame=" + bridgeFrame);
        }
    }

    private void ensureCameraCopyBitmap() {
        if (cameraCopyBitmap != null
                && cameraCopyBitmap.getWidth() == textureWidth
                && cameraCopyBitmap.getHeight() == textureHeight) {
            return;
        }
        if (cameraCopyBitmap != null) {
            cameraCopyBitmap.recycle();
        }
        cameraCopyBitmap = Bitmap.createBitmap(textureWidth, textureHeight,
                Bitmap.Config.ARGB_8888);
    }

    private void drawBitmapToMediaSurface(Bitmap frame) {
        Surface surface = mediaInputSurface;
        if (surface == null || !surface.isValid()) {
            return;
        }
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            drawCameraFrame(canvas, frame);
            bridgeFrame++;
        } catch (RuntimeException e) {
            log("surface copy failed: " + shortError(e));
            handler.removeCallbacks(textureCopyRunnable);
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (RuntimeException e) {
                    log("surface copy post failed: " + shortError(e));
                }
            }
        }
    }

    private void drawNoFrameToMediaSurface(String message) {
        Surface surface = mediaInputSurface;
        if (surface == null || !surface.isValid()) {
            return;
        }
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            drawNoFrame(canvas, message);
            bridgeFrame++;
        } catch (RuntimeException e) {
            log("no-frame draw failed: " + shortError(e));
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (RuntimeException e) {
                    log("no-frame post failed: " + shortError(e));
                }
            }
        }
    }

    private void drawCameraFrame(Canvas canvas, Bitmap frame) {
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF destination = new RectF(0, 0, canvas.getWidth(), canvas.getHeight());
        Rect source = centerCropSource(frame.getWidth(), frame.getHeight(),
                canvas.getWidth(), canvas.getHeight());
        canvas.drawBitmap(frame, source, destination, paint);
        int avg = averageBrightness(frame);
        drawBridgeOverlay(canvas, "camera avg=" + avg
                + " updates=" + textureUpdateCount
                + " frame=" + bridgeFrame);
        if (bridgeFrame % 20 == 0) {
            log("texture bridge frame=" + bridgeFrame
                    + " avg=" + avg
                    + " updates=" + textureUpdateCount);
        }
    }

    private Rect centerCropSource(int sourceWidth, int sourceHeight,
            int targetWidth, int targetHeight) {
        float sourceAspect = sourceWidth / (float) sourceHeight;
        float targetAspect = targetWidth / (float) targetHeight;
        if (sourceAspect > targetAspect) {
            int cropWidth = Math.max(1, Math.round(sourceHeight * targetAspect));
            int left = (sourceWidth - cropWidth) / 2;
            return new Rect(left, 0, left + cropWidth, sourceHeight);
        }
        int cropHeight = Math.max(1, Math.round(sourceWidth / targetAspect));
        int top = (sourceHeight - cropHeight) / 2;
        return new Rect(0, top, sourceWidth, top + cropHeight);
    }

    private void drawNoFrame(Canvas canvas) {
        canvas.drawColor(Color.rgb(40, 0, 24));
        drawBridgeOverlay(canvas, "NO CAMERA FRAME updates=" + textureUpdateCount
                + " frame=" + bridgeFrame);
    }

    private void drawNoFrame(Canvas canvas, String message) {
        canvas.drawColor(Color.rgb(40, 0, 24));
        drawBridgeOverlay(canvas, message);
    }

    private void drawSurfaceBridgeOverlay(Canvas canvas) {
        canvas.drawColor(Color.rgb(0, 10, 18));
        drawBridgeOverlay(canvas, "SURFACEVIEW CAMERA SOURCE frame=" + bridgeFrame);
    }

    private void drawBridgeOverlay(Canvas canvas, String text) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(210, 0, 0, 0));
        canvas.drawRect(0, 0, canvas.getWidth(), Math.max(96, canvas.getHeight() / 9), paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(34f, canvas.getHeight() / 22f));
        paint.setFakeBoldText(true);
        canvas.drawText(text, canvas.getWidth() / 2f,
                Math.max(58f, canvas.getHeight() / 16f), paint);
    }

    private int averageBrightness(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return 0;
        }
        long sum = 0L;
        int count = 0;
        int stepX = Math.max(1, width / 16);
        int stepY = Math.max(1, height / 9);
        for (int y = stepY / 2; y < height; y += stepY) {
            for (int x = stepX / 2; x < width; x += stepX) {
                int color = bitmap.getPixel(x, y);
                sum += (Color.red(color) + Color.green(color) + Color.blue(color)) / 3;
                count++;
            }
        }
        return count == 0 ? 0 : (int) (sum / count);
    }

    private void drawPatternFrame() {
        Surface surface = mediaInputSurface;
        if (surface == null || !surface.isValid()) {
            return;
        }
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            int width = canvas.getWidth();
            int height = canvas.getHeight();
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            int frame = patternFrame++;
            canvas.drawColor(Color.rgb(3, 8, 16));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(0, 210, 180));
            canvas.drawRect(0, 0, width / 2f, height, paint);
            paint.setColor(Color.rgb(255, 216, 64));
            canvas.drawRect(width / 2f, 0, width, height, paint);
            paint.setColor(Color.rgb(220, 30, 70));
            int box = Math.max(80, width / 10);
            int x = (frame * 37) % Math.max(1, width - box);
            canvas.drawRect(x, height / 3f, x + box, height * 2f / 3f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(6f, width / 180f));
            paint.setColor(Color.WHITE);
            canvas.drawRect(4, 4, width - 4, height - 4, paint);
            canvas.drawLine(width / 2f, 0, width / 2f, height, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(Math.max(42f, height / 12f));
            paint.setColor(Color.BLACK);
            canvas.drawText("DASH PROJECTION TEST", width / 2f, height * 0.46f, paint);
            paint.setTextSize(Math.max(28f, height / 20f));
            canvas.drawText("frame " + frame, width / 2f, height * 0.58f, paint);
        } catch (RuntimeException e) {
            log("pattern draw failed: " + shortError(e));
            handler.removeCallbacks(patternRunnable);
        } finally {
            if (canvas != null) {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (RuntimeException e) {
                    log("pattern post failed: " + shortError(e));
                }
            }
        }
    }

    private void callCreateClient() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        String clientPackage = clientPackageName();
        try {
            data.writeInterfaceToken(API_DESCRIPTOR);
            data.writeStrongBinder(apiClient);
            data.writeString(clientPackage);
            transact(TX_CREATE_CLIENT, data, reply);
            reply.readException();
            log("create client ok package=" + clientPackage);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private String clientPackageName() {
        return stringExtra("client_package", getPackageName());
    }

    private String controlPackageName() {
        return stringExtra("control_package", "com.byd.dishare");
    }

    private String controlAppName() {
        return stringExtra("app", clientPackageName());
    }

    private String stringExtra(String name, String fallback) {
        String value = getIntent().getStringExtra(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private void callRemoveClient() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESCRIPTOR);
            data.writeStrongBinder(apiClient);
            transact(TX_REMOVE_CLIENT, data, reply);
            reply.readException();
            log("remove client ok");
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void callSetVideoSize(int width, int height) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESCRIPTOR);
            data.writeStrongBinder(apiClient);
            data.writeInt(width);
            data.writeInt(height);
            transact(TX_SET_VIDEO_SIZE, data, reply);
            reply.readException();
            log("set video size " + width + "x" + height);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void callSetVideoBounds(Rect bounds) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESCRIPTOR);
            data.writeStrongBinder(apiClient);
            data.writeInt(1);
            bounds.writeToParcel(data, 0);
            transact(TX_SET_VIDEO_BOUNDS, data, reply);
            reply.readException();
            log("set video bounds " + bounds);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void callSetGestureShare(boolean enabled) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESCRIPTOR);
            data.writeStrongBinder(apiClient);
            data.writeInt(enabled ? 1 : 0);
            transact(TX_SET_GESTURE_SHARE, data, reply);
            reply.readException();
            log("set gesture " + enabled);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void callSetMirrorSourceClient() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESCRIPTOR);
            data.writeStrongBinder(apiClient);
            data.writeStrongBinder(mirrorSourceClient);
            transact(TX_SET_MIRROR_SOURCE_CLIENT, data, reply);
            reply.readException();
            log("set mirror source client ok");
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void callQuickShare(String target) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESCRIPTOR);
            data.writeStrongBinder(apiClient);
            data.writeString(target);
            transact(TX_QUICK_SHARE, data, reply);
            reply.readException();
            log("quick share target=" + target);
        } catch (RuntimeException e) {
            log("quick share failed: " + shortError(e));
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void callFinishShare() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESCRIPTOR);
            data.writeStrongBinder(apiClient);
            transact(TX_FINISH_SHARE, data, reply);
            reply.readException();
            log("finish share ok");
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transact(int code, Parcel data, Parcel reply) {
        if (apiBinder == null) {
            throw new IllegalStateException("api binder is null");
        }
        try {
            if (!apiBinder.transact(code, data, reply, 0)) {
                throw new IllegalStateException("transact returned false code=" + code);
            }
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    private void log(String message) {
        String line = System.currentTimeMillis() + " " + message;
        Log.i(TAG, message);
        lastStatus = lastStatus + line + "\n";
        if (statusView != null) {
            statusView.setText(message);
        }
        try (FileOutputStream output = new FileOutputStream(statusFile, true)) {
            output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "status write failed", e);
        }
    }

    private void resetStatus() {
        lastStatus = "";
        try (FileOutputStream output = new FileOutputStream(statusFile, false)) {
            output.write(new byte[0]);
        } catch (IOException e) {
            Log.w(TAG, "status reset failed", e);
        }
    }

    private static String shortError(Throwable error) {
        if (error == null) {
            return "null";
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private final class ApiClientBinder extends Binder {
        ApiClientBinder() {
            attachInterface(null, API_CLIENT_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(API_CLIENT_DESCRIPTOR);
                return true;
            }
            if (code == 1) {
                data.enforceInterface(API_CLIENT_DESCRIPTOR);
                boolean active = data.readInt() != 0;
                log("api client active=" + active);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private final class MirrorSourceClientBinder extends Binder {
        MirrorSourceClientBinder() {
            attachInterface(null, MIRROR_SOURCE_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(MIRROR_SOURCE_DESCRIPTOR);
                return true;
            }
            data.enforceInterface(MIRROR_SOURCE_DESCRIPTOR);
            switch (code) {
                case 1:
                    int event = data.readInt();
                    Bundle eventBundle = readBundle(data);
                    log("mirror event code=" + event + " bundle=" + eventBundle);
                    reply.writeNoException();
                    return true;
                case 2:
                    String name = data.readString();
                    int clientPort = data.readInt();
                    int bufferSize = data.readInt();
                    Bundle params = readBundle(data);
                    int protocol = params == null ? 0 : params.getInt("protocol_version", 0);
                    int receiverId = 0;
                    log("mirror request name=" + name
                            + " port=" + clientPort
                            + " protocol=" + protocol
                            + " buffer=" + bufferSize
                            + " bundle=" + params);
                    if (mediaStreamEnabled) {
                        try {
                            receiverId = mediaStreamServer.addReceiver(name, clientPort,
                                    protocol, bufferSize);
                            mediaStreamServer.requestKeyFrame();
                        } catch (Exception e) {
                            log("mirror add receiver failed: " + shortError(e));
                            receiverId = -1;
                        }
                    }
                    reply.writeNoException();
                    reply.writeInt(receiverId);
                    return true;
                case 3:
                    int supportCode = data.readInt();
                    Bundle supportParams = readBundle(data);
                    log("mirror support code=" + supportCode + " bundle=" + supportParams);
                    reply.writeNoException();
                    reply.writeInt(1);
                    return true;
                case 4:
                    log("mirror start");
                    if (mediaStreamEnabled) {
                        try {
                            mediaStreamServer.start();
                            mediaStreamServer.requestKeyFrame();
                            log("media stream start ok");
                        } catch (Exception e) {
                            log("media stream start failed: " + shortError(e));
                        }
                    } else {
                        handler.post(HudDiShareActivity.this::launchImage);
                    }
                    reply.writeNoException();
                    return true;
                case 5:
                    log("mirror stop");
                    if (mediaStreamEnabled) {
                        mediaStreamServer.stop();
                    }
                    reply.writeNoException();
                    return true;
                case 6:
                    data.readStrongBinder();
                    log("mirror set listener Z");
                    reply.writeNoException();
                    return true;
                case 7:
                    data.readStrongBinder();
                    log("mirror set listener M");
                    reply.writeNoException();
                    return true;
                case 9:
                    data.readStrongBinder();
                    log("mirror screenshot callback");
                    reply.writeNoException();
                    return true;
                default:
                    log("mirror unknown tx=" + code);
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private Bundle readBundle(Parcel data) {
            if (data.readInt() == 0) {
                return null;
            }
            return Bundle.CREATOR.createFromParcel(data);
        }
    }
}
