package com.byd.cluster.projection.mapdemo;

import android.app.Activity;
import android.app.Presentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
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
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class AvcAidlDashActivity extends Activity {
    private static final String TAG = "DenzaProjectionProbe";
    private static final String CLUSTER_DISPLAY_NAME = "shared_fission_bg_XDJAScreenProjection_0";
    private static final int DEFAULT_DISPLAY_ID = 3;
    private static final int DEFAULT_VIEWPOINT = 2002; // SUB_CAMERA_LEFT
    private static final long DEFAULT_DURATION_MS = 20000L;
    private static final String DEFAULT_SLOT = "full";
    private static final int DEFAULT_CENTER_EXTEND_PERCENT = 20;
    static final String EXTRA_FINISH = "finish";

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
        int centerExtendPercent = Math.max(0, Math.min(100,
                intent.getIntExtra("center_extend_percent", DEFAULT_CENTER_EXTEND_PERCENT)));
        boolean overlayWindow = intent.getBooleanExtra("overlay_window", true);
        long durationMs = Math.max(1000L, intent.getLongExtra("duration_ms", DEFAULT_DURATION_MS));
        Display display = findClusterDisplay(this, requestedDisplayId);
        if (display == null) {
            Log.i(TAG, "avc aidl display not found id=" + requestedDisplayId);
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
                    cropSource, centerExtendPercent, overlayWindow);
            Log.i(TAG, "avc aidl presentation shown displayId=" + display.getDisplayId()
                    + " name=" + display.getName()
                    + " viewpoint=" + viewpoint
                    + " slot=" + slot
                    + " cropSource=" + cropSource
                    + " centerExtendPercent=" + centerExtendPercent
                    + " overlayWindow=" + overlayWindow
                    + " uturn=" + uTurnEnabled);
        } catch (RuntimeException e) {
            Log.i(TAG, "avc aidl presentation show failed", e);
            finishAndRemoveTask();
            return;
        }

        handler.postDelayed(this::finishAndRemoveTask, durationMs);
    }

    private AvcPresentation showPresentation(Display display, int viewpoint,
            boolean uTurnEnabled, String slot, String cropSource,
            int centerExtendPercent, boolean overlayWindow) {
        AvcPresentation first = new AvcPresentation(this, display, viewpoint, uTurnEnabled,
                slot, cropSource, centerExtendPercent, overlayWindow);
        try {
            first.show();
            return first;
        } catch (RuntimeException e) {
            if (!overlayWindow) {
                throw e;
            }
            Log.i(TAG, "overlay presentation failed, retrying normal window", e);
            AvcPresentation fallback = new AvcPresentation(this, display, viewpoint,
                    uTurnEnabled, slot, cropSource, centerExtendPercent, false);
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

    static boolean finishActiveInstance() {
        AvcAidlDashActivity activity = activeInstance;
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(activity::finishFast);
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

    private static String shortError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + " " + message;
    }

    static final class AvcPresentation extends Presentation
            implements SurfaceHolder.Callback, TextureView.SurfaceTextureListener {
        private final int viewpoint;
        private final boolean uTurnEnabled;
        private final String slot;
        private final String cropSource;
        private final int centerExtendPercent;
        private final boolean overlayWindow;
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
                int centerExtendPercent, boolean overlayWindow) {
            super(outerContext, display);
            this.viewpoint = viewpoint;
            this.uTurnEnabled = uTurnEnabled;
            this.slot = slot;
            this.cropSource = cropSource;
            this.centerExtendPercent = centerExtendPercent;
            this.overlayWindow = overlayWindow;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            configurePresentationWindow();
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.TRANSPARENT);

            FrameLayout surfaceFrame = new FrameLayout(getContext());
            surfaceFrame.setBackgroundColor(Color.BLACK);
            root.addView(surfaceFrame, buildSurfaceFrameParams());

            textureView = new TextureView(getContext());
            textureView.setOpaque(true);
            textureView.setSurfaceTextureListener(this);
            surfaceFrame.addView(textureView, buildSurfaceViewParams(surfaceFrame));

            setContentView(root);
            bindAvcService();
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
            if ("full".equals(slot)) {
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
            if ("right".equals(slot)) {
                gravity = Gravity.END;
            } else if ("center".equals(slot)) {
                gravity = Gravity.CENTER_HORIZONTAL;
                frameWidth = slotWidth;
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
            File statusFile = new File(getContext().getFilesDir(), "avc_aidl_status.txt");
            try (FileOutputStream output = new FileOutputStream(statusFile, false)) {
                output.write(status.getBytes(StandardCharsets.UTF_8));
                output.write('\n');
            } catch (IOException e) {
                Log.i(TAG, "status file write failed", e);
            }
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
