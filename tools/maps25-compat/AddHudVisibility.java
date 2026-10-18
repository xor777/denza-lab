import java.io.*;
import java.util.*;
import java.util.zip.*;
import com.bydhud.gmapsdiag.patcher.HudPackageVisibilityPatcher;

/** The BYD HUD Android pipeline runs this step separately from its DEX CLI. */
public final class AddHudVisibility {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input.apk output.apk");
        boolean found = false;
        try (ZipFile input = new ZipFile(args[0]); ZipOutputStream output = new ZipOutputStream(new FileOutputStream(args[1]))) {
            for (ZipEntry entry : Collections.list(input.entries())) {
                if (entry.getName().startsWith("META-INF/")) continue;
                byte[] bytes = input.getInputStream(entry).readAllBytes();
                if (entry.getName().equals("AndroidManifest.xml")) {
                    bytes = HudPackageVisibilityPatcher.patch(bytes);
                    if (!HudPackageVisibilityPatcher.hasQuery(bytes)) throw new IOException("HUD visibility missing");
                    found = true;
                }
                ZipEntry target = new ZipEntry(entry.getName());
                target.setMethod(entry.getMethod()); target.setTime(entry.getTime());
                if (target.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32(); crc.update(bytes); target.setSize(bytes.length); target.setCrc(crc.getValue());
                }
                output.putNextEntry(target); output.write(bytes); output.closeEntry();
            }
        }
        if (!found) throw new IOException("Missing manifest");
    }
}
