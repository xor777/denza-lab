package com.byd.spi.ipc.cursor;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Local stand-in for the vendor class of the same name.
 *
 * <p>The car service provider answers a lookup with a cursor whose extras hold one parcelable
 * carrying the service binder. That parcelable is written by its fully qualified name, and the
 * name is all the platform needs to unmarshal it, so this file recreates the class under the same
 * name with the same one-field layout. Nothing of the vendor implementation is reproduced beyond
 * the wire shape needed to read a strong binder back out.
 */
public class BinderCursor {

    /** Extras key the provider writes the binder under. */
    public static final String KEY_BINDER = "binder";

    public static class BinderParcelable implements Parcelable {

        public static final Creator<BinderParcelable> CREATOR = new Creator<BinderParcelable>() {
            @Override
            public BinderParcelable createFromParcel(Parcel source) {
                return new BinderParcelable(source.readStrongBinder());
            }

            @Override
            public BinderParcelable[] newArray(int size) {
                return new BinderParcelable[size];
            }
        };

        private final IBinder binder;

        public BinderParcelable(IBinder binder) {
            this.binder = binder;
        }

        public IBinder getBinder() {
            return binder;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeStrongBinder(binder);
        }
    }
}
