package dev.denza.adaslink;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Public Android BOOT_COUNT scoped together with the random consumer-process UUID. */
public final class BootIdentity {
    private BootIdentity(){}
    public static String fromCount(String raw) {
        if(raw==null || !raw.matches("0|[1-9][0-9]{0,9}"))throw new IllegalArgumentException("boot count missing");
        long value=Long.parseLong(raw);
        if(value>Integer.MAX_VALUE)throw new IllegalArgumentException("boot count range");
        return UUID.nameUUIDFromBytes(("denza-adas-boot-count-v1:"+raw).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
