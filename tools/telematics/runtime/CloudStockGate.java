package dev.denza.tools.runtime;

import android.os.IBinder;
import android.os.Parcel;
import java.io.IOException;

/** Guardian-side stock owner recovery, independent of the Java session process. */
final class CloudStockGate {
    interface BinderAccess { int tcp() throws Exception; String profile() throws Exception; }
    private static final class AndroidAccess implements BinderAccess {
        private IBinder service()throws Exception {
            IBinder binder=(IBinder)Class.forName("android.os.ServiceManager")
                .getMethod("getService",String.class).invoke(null,"cloudmanager");
            if(binder==null)throw new IOException("stock_service_unavailable");return binder;
        }
        public int tcp()throws Exception{
            IBinder b=service();Parcel q=Parcel.obtain(),r=Parcel.obtain();
            try{q.writeInterfaceToken(b.getInterfaceDescriptor());
                if(!b.transact(7,q,r,0))throw new IOException("stock_tcp_transaction");
                r.readException();int value=r.readInt();
                if(value!=0&&value!=1)throw new IOException("stock_tcp_value");return value;
            }finally{q.recycle();r.recycle();}
        }
        public String profile()throws Exception{
            return (String)Class.forName("android.os.SystemProperties")
                .getMethod("get",String.class).invoke(null,"persist.sys.byd.apn_type");
        }
    }
    static BinderAccess android(){return new AndroidAccess();}
    private CloudStockGate(){}
}
