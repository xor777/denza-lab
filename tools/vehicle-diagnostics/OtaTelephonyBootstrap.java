/** ActivityThread.main's process-local telephony initialization for app_process.
 * No ServiceRegisterer.register, service launch, SIM state change or device write.
 */
public final class OtaTelephonyBootstrap {
    static boolean prepare() throws Exception {
        Class<?> initializer=Class.forName("android.telephony.TelephonyFrameworkInitializer");
        Object existing=initializer.getMethod("getTelephonyServiceManager").invoke(null);
        if(existing!=null)return false;
        Class<?> manager=Class.forName("android.os.TelephonyServiceManager");
        initializer.getMethod("setTelephonyServiceManager",manager).invoke(null,manager.getConstructor().newInstance());
        if(initializer.getMethod("getTelephonyServiceManager").invoke(null)==null)throw new IllegalStateException();
        return true;
    }
}
