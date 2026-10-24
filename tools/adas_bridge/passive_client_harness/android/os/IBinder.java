package android.os;
public interface IBinder {
    int INTERFACE_TRANSACTION = 1598968902;
    boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException;
    boolean isBinderAlive();
}
