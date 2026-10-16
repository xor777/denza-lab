package dev.denza.vehiclecontrol.probe;
import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.*;
final class VoiceModelFiles {
    private static final String MODEL_ROOT="vosk-model-small-ru-0.22", TAG="DenzaVoiceModel";
    interface Check { void run() throws Exception; }
    static synchronized File install(Context context, Check check) throws Exception {
        String expected;
        try (InputStream input = context.getAssets().open("voice-model.sha256")) {
            expected = new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        }
        if (!expected.matches("[0-9a-f]{64}")) throw new IOException("Некорректный SHA-256 офлайн-модели");
        File directory = new File(context.getFilesDir(), "voice-model");
        File installed = new File(directory, MODEL_ROOT);
        File marker = new File(directory, "installed.sha256");
        if (marker.isFile() && new File(installed, "am/final.mdl").isFile()) {
            try (InputStream input = new FileInputStream(marker)) {
                if (expected.equals(new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim())) return installed;
            }
        }
        File archive = new File(context.getFilesDir(), "voice-model.zip.tmp");
        File staging = new File(context.getFilesDir(), "voice-model.installing");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IOException("Не удалось подготовить каталог модели");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65_536];
            try (InputStream input = context.getAssets().open("voice-model.zip"); FileOutputStream output = new FileOutputStream(archive)) {
                int count;
                while ((count = input.read(buffer)) != -1) { check.run(); digest.update(buffer, 0, count); output.write(buffer, 0, count); }
                output.getFD().sync();
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
            if (!expected.contentEquals(actual)) throw new IOException("SHA-256 офлайн-модели не совпал");
            long extracted = 0;
            int entries = 0;
            String rootPath = new File(staging, MODEL_ROOT).getCanonicalPath();
            try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    check.run();
                    String name = entry.getName();
                    File target = new File(staging, name);
                    String targetPath = target.getCanonicalPath();
                    if (++entries > 2_000 || name.contains("\\") || !name.startsWith(MODEL_ROOT + "/")
                            || !(targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator)))
                        throw new IOException("Небезопасный ZIP модели");
                    if (entry.isDirectory()) { if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Каталог ZIP не создан"); }
                    else {
                        File parent = target.getParentFile();
                        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Каталог модели не создан");
                        try (FileOutputStream output = new FileOutputStream(target)) {
                            int count;
                            while ((count = zip.read(buffer)) != -1) {
                                check.run();
                                extracted += count;
                                if (extracted > 256L * 1_024 * 1_024) throw new IOException("ZIP модели превышает лимит");
                                output.write(buffer, 0, count);
                            }
                        }
                    }
                    zip.closeEntry();
                }
            }
            if (!new File(staging, MODEL_ROOT + "/am/final.mdl").isFile()) throw new IOException("Неполная офлайн-модель");
            check.run();
            deleteTree(directory);
            if (!staging.renameTo(directory)) throw new IOException("Не удалось установить офлайн-модель");
            AtomicFile atomic = new AtomicFile(marker);
            FileOutputStream output = atomic.startWrite();
            try { output.write(expected.getBytes(StandardCharsets.US_ASCII)); atomic.finishWrite(output); }
            catch (IOException failure) { atomic.failWrite(output); throw failure; }
            return installed;
        } finally {
            if (archive.exists() && !archive.delete()) Log.w(TAG, "Could not remove temporary model ZIP");
            deleteTree(staging);
        }
    }

    private static void deleteTree(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (file.exists() && !file.delete()) throw new IOException("Не удалось удалить временную модель: " + file.getName());
    }
}
