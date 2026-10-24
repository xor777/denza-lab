package android.os;
public class Binder implements IBinder {
    public void attachInterface(Object owner, String descriptor) { }
    @Override public boolean isBinderAlive() { return true; }
    @Override public final boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        data.rewind(); boolean result = onTransact(code, data, reply, flags);
        if (reply != null) reply.rewind(); return result;
    }
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException { return false; }
}
