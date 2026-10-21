import android.content.Context;
import android.content.ContextWrapper;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.telephony.TelephonyManager;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Fixed stock login inputs, in process memory only. No OEM device constructors. */
public final class OtaLoginInputs {
    static RandomAccessFile lockFile;
    static FileLock lock;

    static String property(String name, String fallback) throws Exception {
        return (String)Class.forName("android.os.SystemProperties")
            .getMethod("get", String.class, String.class).invoke(null, name, fallback);
    }

    static OtaLoginProtocol.Identity read() throws Exception {
        lockFile = new RandomAccessFile("/data/local/tmp/denza-dtc-read.lock", "rw");
        lock = lockFile.getChannel().tryLock();
        EcuMapMetadataProbe.require(lock != null, "login_inputs_busy");
        EcuMapMetadataProbe.require("170".equals(property("persist.sys.AutoType", "")), "login_inputs_profile");
        String bench = property("persist.sys.byd.bench_ota_switch", "0");
        EcuMapMetadataProbe.require(Arrays.asList("0", "false", "off", "no", "").contains(bench), "login_inputs_bench");
        byte[] vinBytes;
        try {
            IBinder service = (IBinder)Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "autoservice");
            Parcel request = Parcel.obtain(), reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(service.getInterfaceDescriptor());
                request.writeInt(1001); request.writeInt(0x9900021a);
                EcuMapMetadataProbe.require(service.transact(13, request, reply, 0), "login_inputs_vin");
                reply.readException();
                vinBytes = reply.createByteArray();
                EcuMapMetadataProbe.require(vinBytes != null && vinBytes.length == 17 && reply.dataAvail() == 0, "login_inputs_vin");
            } finally { request.recycle(); reply.recycle(); }
        } catch (Throwable failure) { throw new EcuMapMetadataProbe.Failure("login_inputs_vin"); }
        String vin = new String(vinBytes, StandardCharsets.US_ASCII);
        Arrays.fill(vinBytes, (byte)0);
        String iccid;
        String phase = "login_inputs_context";
        try {
            OtaTelephonyBootstrap.prepare();
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            Class<?> activity = Class.forName("android.app.ActivityThread");
            Object thread = activity.getMethod("systemMain").invoke(null);
            Context context = (Context)activity.getMethod("getSystemContext").invoke(thread);
            // Use the actual shell package for the service's calling-package check.
            EcuMapMetadataProbe.require(context.getPackageManager().getPackageUid("com.android.shell", 0)
                    == android.os.Process.myUid(), "login_inputs_package");
            // This framework's createPackageContext inherits the container's
            // op package. Attribute the call to our actual UID-owned package.
            Context shell = new ContextWrapper(context.createPackageContext("com.android.shell", 0)) {
                @Override public String getPackageName() { return "com.android.shell"; }
                @Override public String getOpPackageName() { return "com.android.shell"; }
                @Override public Context getApplicationContext() { return this; }
            };
            EcuMapMetadataProbe.require("com.android.shell".equals(shell.getOpPackageName()), "login_inputs_package");
            phase = "login_inputs_phone";
            TelephonyManager phone = TelephonyManager.class.getConstructor(Context.class).newInstance(shell);
            EcuMapMetadataProbe.require(phone != null, "login_inputs_phone");
            phase = "login_inputs_iccid_getter";
            iccid = phone.getSimSerialNumber();
        } catch (EcuMapMetadataProbe.Failure failure) { throw failure;
        } catch (SecurityException failure) { throw new EcuMapMetadataProbe.Failure("login_inputs_permission");
        } catch (Throwable failure) { throw new EcuMapMetadataProbe.Failure(phase); }
        return new OtaLoginProtocol.Identity(vin, iccid, property("sys.byd.countrycode", "86"));
    }
}
