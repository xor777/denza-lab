package dev.denza.hud.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opaque native route evidence. Creating the offline serializer does not request or replace a route. */
final class RouteArchive {
    final byte[] bytes;
    final Map<String, Object> metadata;
    private RouteArchive(byte[] bytes, Map<String, Object> metadata) { this.bytes = bytes; this.metadata = metadata; }

    static RouteArchive read(Object route, String routeId, Reflect api) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("schema", "denza-yandex-route-archive-v1");
        info.put("routeId", routeId);
        info.put("sourceSessionId", NavigationEvidence.SOURCE_SESSION_ID);
        info.put("capturedAtMs", System.currentTimeMillis());
        info.put("opaque", true);
        info.put("format", "DrivingRouter.routeSerializer().save; not yet decoded");
        try {
            Object directions = api.call(api.type("com.yandex.mapkit.directions.DirectionsFactory"), "getInstance");
            Object offline = api.type("com.yandex.mapkit.directions.driving.DrivingRouterType").getField("OFFLINE").get(null);
            Object router = api.call(directions, "createDrivingRouter", offline);
            Object serializer = api.call(router, "routeSerializer");
            byte[] data = (byte[]) api.call(serializer, "save", route);
            if (data == null || data.length == 0 || data.length > 16 * 1024 * 1024)
                throw new IOException("Native route archive outside diagnostic size bound");
            data = data.clone();
            StringBuilder digest = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) digest.append(String.format("%02x", value & 255));
            info.put("status", "available"); info.put("bytes", data.length); info.put("sha256", digest.toString());
            return new RouteArchive(data, info);
        } catch (Exception failure) {
            info.put("status", "unknown"); info.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return new RouteArchive(null, info);
        }
    }

    void write(File directory) throws IOException {
        File destination = new File(directory, "denza-adas-route-archive.bin");
        if (bytes != null) {
            File temporary = File.createTempFile("route-archive-", ".tmp", directory);
            try {
                try (FileOutputStream out = new FileOutputStream(temporary)) {
                    out.write(bytes); out.getFD().sync();
                }
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary.toPath()); }
        } else Files.deleteIfExists(destination.toPath());
        RouteSnapshot.writeAtomic(new File(directory, "denza-adas-route-archive.json"), metadata);
    }
}
