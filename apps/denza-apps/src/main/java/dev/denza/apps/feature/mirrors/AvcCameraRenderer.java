package dev.denza.apps.feature.mirrors;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.SurfaceTexture;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

/** Product AVC binder path extracted from the verified Denza Mirrors renderer. */
public final class AvcCameraRenderer implements TextureView.SurfaceTextureListener {
    public interface Listener {
        void onReady(String details);
        void onFailure(String details);
    }

    private static final float COLOR_CONTRAST_SCALE = 1.62f;
    private static final float COLOR_BRIGHTNESS_OFFSET = 28.0f;
    private static final float COLOR_SATURATION = 0.80f;

    private final Context context;
    private final TextureView textureView;
    private final Listener listener;
    private AvcAidlClient client;
    private boolean bound;
    private boolean bindingRequested;
    private boolean initAttempted;
    private int viewpoint;
    private Surface surface;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            client = new AvcAidlClient(service);
            bound = true;
            bindingRequested = true;
            initializeIfReady();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            bindingRequested = false;
            client = null;
            listener.onFailure("com.byd.avc disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            bound = false;
            bindingRequested = false;
            client = null;
            listener.onFailure("com.byd.avc binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
            bound = false;
            bindingRequested = false;
            client = null;
            listener.onFailure("com.byd.avc returned no binder");
        }
    };

    public AvcCameraRenderer(Context context, TextureView textureView, Listener listener) {
        this.context = context;
        this.textureView = textureView;
        this.listener = listener;
    }

    public void start(int viewpoint, boolean processingEnabled) {
        stop();
        this.viewpoint = viewpoint;
        applyImageEnhancement(processingEnabled);
        textureView.setSurfaceTextureListener(this);
        if (textureView.isAvailable() && textureView.getSurfaceTexture() != null) {
            onSurfaceTextureAvailable(
                    textureView.getSurfaceTexture(), textureView.getWidth(), textureView.getHeight());
        }
        Intent intent = new Intent("com.byd.avc.aidl.service").setPackage("com.byd.avc");
        try {
            bindingRequested = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bindingRequested) {
                listener.onFailure("com.byd.avc bind failed");
            }
        } catch (RuntimeException error) {
            listener.onFailure("com.byd.avc bind failed: " + shortError(error));
        }
    }

    public void stop() {
        if (client != null) {
            try {
                client.freeDisplay();
            } catch (RemoteException | RuntimeException ignored) {
                // The vendor service may already be gone; local cleanup must still finish.
            }
        }
        if (bound || bindingRequested) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Already unbound by the platform.
            }
        }
        bound = false;
        bindingRequested = false;
        client = null;
        initAttempted = false;
        releaseSurface();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        texture.setDefaultBufferSize(Math.max(1, width), Math.max(1, height));
        releaseSurface();
        surface = new Surface(texture);
        initializeIfReady();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        texture.setDefaultBufferSize(Math.max(1, width), Math.max(1, height));
        initializeIfReady();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        releaseSurface();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    private void initializeIfReady() {
        if (!bound || client == null || surface == null || !surface.isValid() || initAttempted) {
            return;
        }
        initAttempted = true;
        try {
            String name = client.getName();
            int bufferType = client.getSupportPushBufferType();
            boolean initialized = client.initDisplay(surface);
            client.setViewpoint(viewpoint);
            listener.onReady("avc=" + name + " init=" + initialized
                    + " buffer=" + bufferType + " viewpoint=" + viewpoint);
        } catch (RemoteException | RuntimeException error) {
            listener.onFailure("com.byd.avc init failed: " + shortError(error));
        }
    }

    private void applyImageEnhancement(boolean enabled) {
        if (!enabled) {
            textureView.setRenderEffect(null);
            textureView.setLayerType(View.LAYER_TYPE_NONE, null);
            return;
        }
        float contrast = COLOR_CONTRAST_SCALE;
        float offset = (-0.5f * contrast + 0.5f) * 255f + COLOR_BRIGHTNESS_OFFSET;
        ColorMatrix contrastMatrix = new ColorMatrix(new float[] {
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f
        });
        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(COLOR_SATURATION);
        saturation.postConcat(contrastMatrix);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(saturation);
        Paint paint = new Paint();
        paint.setColorFilter(filter);
        textureView.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
        textureView.setRenderEffect(RenderEffect.createColorFilterEffect(filter));
    }

    private void releaseSurface() {
        if (surface != null) surface.release();
        surface = null;
    }

    private static String shortError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : " " + message);
    }

    private static final class AvcAidlClient {
        private static final String DESCRIPTOR = "com.byd.avc.aidl.IAVCAidlInterface";
        private static final int GET_NAME = 1;
        private static final int SET_VIEWPOINT = 5;
        private static final int GET_PUSH_BUFFER_TYPE = 6;
        private static final int INIT_DISPLAY = 7;
        private static final int FREE_DISPLAY = 9;
        private final IBinder remote;

        AvcAidlClient(IBinder remote) {
            this.remote = remote;
        }

        String getName() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(GET_NAME, data, reply, 0);
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
                remote.transact(GET_PUSH_BUFFER_TYPE, data, reply, 0);
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
                remote.transact(INIT_DISPLAY, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        void setViewpoint(int value) throws RemoteException {
            transactInt(SET_VIEWPOINT, value);
        }

        void freeDisplay() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(FREE_DISPLAY, data, reply, 0);
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private void transactInt(int code, int value) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(value);
                remote.transact(code, data, reply, 0);
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }
}
