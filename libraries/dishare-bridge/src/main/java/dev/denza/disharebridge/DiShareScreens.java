package dev.denza.disharebridge;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DiShareScreens {
    public interface Callback {
        void onScreens(List<Screen> screens);

        void onFailed(String message);
    }

    public static final class Screen {
        public final String deviceId;
        public final String screenId;
        public final boolean available;

        Screen(String deviceId, String screenId, boolean available) {
            this.deviceId = deviceId;
            this.screenId = screenId;
            this.available = available;
        }

        @Override
        public String toString() {
            return "Screen{deviceId=" + deviceId
                    + ", screenId=" + screenId
                    + ", available=" + available + '}';
        }
    }

    private static final String TAG = "DenzaDiShareScreens";
    private static final String CONTROL_ACTION = "com.byd.dishare.control.DiShareControlService";
    private static final String CONTROL_DESCRIPTOR = "com.byd.dishare.control.IDiShareControl";
    private static final String CONTROL_PACKAGE = "com.byd.dishare";
    private static final int TX_GET_SCREENS = 0x4;

    private DiShareScreens() {
    }

    public static void query(Context context, String packageName, Callback callback) {
        new Query(context.getApplicationContext(), packageName, callback).start();
    }

    private static final class Query {
        private final String packageName;
        private final Callback callback;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final DiShareBinding binding;
        private boolean finished;

        Query(Context context, String packageName, Callback callback) {
            this.packageName = packageName == null || packageName.trim().isEmpty()
                    ? CONTROL_PACKAGE : packageName.trim();
            this.callback = callback;
            this.binding = new DiShareBinding(context, CONTROL_ACTION,
                    new DiShareBinding.Listener() {
                        @Override
                        public void onConnected(IBinder binder) {
                            List<Screen> screens;
                            try {
                                screens = readScreens(binder);
                            } catch (RuntimeException e) {
                                fail(shortError(e));
                                return;
                            }
                            deliver(screens);
                        }

                        @Override
                        public void onDisconnected() {
                            fail("disconnected");
                        }
                    });
        }

        void start() {
            boolean bound;
            try {
                bound = binding.bind();
            } catch (RuntimeException e) {
                fail("bind failed: " + shortError(e));
                return;
            }
            if (!bound) {
                fail("bind returned false");
                return;
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    fail("timeout");
                }
            }, 3000L);
        }

        private List<Screen> readScreens(IBinder controlBinder) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeString(packageName);
                if (!controlBinder.transact(TX_GET_SCREENS, data, reply, 0)) {
                    throw new IllegalStateException("getScreens transact failed");
                }
                reply.readException();
                int size = reply.readInt();
                if (size < 0) {
                    return Collections.emptyList();
                }
                List<Screen> screens = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    if (reply.readInt() == 0) {
                        continue;
                    }
                    screens.add(new Screen(reply.readString(), reply.readString(),
                            reply.readByte() != 0));
                }
                return Collections.unmodifiableList(screens);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        /** Exactly one of the two callbacks, once; a throwing onScreens is not also a failure. */
        private void deliver(List<Screen> screens) {
            if (!finish()) {
                return;
            }
            try {
                callback.onScreens(screens);
            } catch (RuntimeException e) {
                Log.w(TAG, "screens callback failed", e);
            }
        }

        private void fail(String message) {
            if (!finish()) {
                return;
            }
            callback.onFailed(message);
        }

        /** Returns false when the query had already finished. */
        private boolean finish() {
            if (finished) {
                return false;
            }
            finished = true;
            handler.removeCallbacksAndMessages(null);
            binding.release();
            return true;
        }
    }

    private static String shortError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
