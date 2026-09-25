package dev.denza.tools.runtime;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/** Compatibility gate for the reviewed OTA, not a claim to have read protected cloudmanager. */
public final class CloudFirmwareProfile {
    public interface Probe {
        String property(String key) throws Exception;
        String sha256(Path path) throws Exception;
    }
    private static final String FINGERPRINT =
        "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys";
    private static final String[][] LIBRARIES = {
        {"/system/lib64/libbydauto.so", "1730ca60779d7d45f2976a947172f6c0b4b5145d763d07bc257d809275537362"},
        {"/system/lib64/libbydautoservice.so", "98f057b5072844420625a530bc26c16c8ba3731ed29d6ef2a752a58f7a65bd65"},
        {"/system/lib64/libsafekeyservice.so", "d6d10902b189b8cb7e8b42b2cf445f01d24407cea3a0c0e44666d1f9f9419f79"}
    };
    private CloudFirmwareProfile() {}
    public static void verify(Probe probe) throws Exception {
        if(!FINGERPRINT.equals(probe.property("ro.build.fingerprint")) ||
           !"green".equals(probe.property("ro.boot.verifiedbootstate")))
            throw new CloudSessionLoop.PermanentFailure(CloudRuntimeSupervisor.Code.UNSUPPORTED_FIRMWARE);
        for(String[] library:LIBRARIES)
            if(!library[1].equals(probe.sha256(Paths.get(library[0]))))
                throw new CloudSessionLoop.PermanentFailure(CloudRuntimeSupervisor.Code.UNSUPPORTED_FIRMWARE);
    }
    public static Probe androidProbe() {
        return new Probe() {
            public String property(String key) throws Exception {
                return (String)Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, key);
            }
            public String sha256(Path path) throws Exception {
                MessageDigest hash=MessageDigest.getInstance("SHA-256");
                try(InputStream input=Files.newInputStream(path)) {
                    byte[] block=new byte[8192];int n;
                    while((n=input.read(block))!=-1)hash.update(block,0,n);
                }
                return CloudNativeConnection.hex(hash.digest());
            }
        };
    }
}
