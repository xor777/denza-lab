package dev.denza.dipilotkey.probe;

import android.content.Context;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;

/**
 * Writes {@code AdbKeyStore}'s {@code adb_auth_key_v1} so {@code LocalAdbClient} loads the
 * Dipilot key instead of generating one.
 */
final class AdbKeyFile {
    static final String FILE_NAME = "adb_auth_key_v1";
    /** {@code AdbKeyStore.KEY_FILE_MAGIC}, ASCII {@code DAK1}. */
    static final int MAGIC = 0x44414b31;

    private AdbKeyFile() {
    }

    static void install(Context context) throws IOException, GeneralSecurityException {
        install(context.getNoBackupFilesDir());
    }

    static void install(File directory) throws IOException, GeneralSecurityException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create " + directory);
        }
        File target = new File(directory, FILE_NAME);
        File temp = new File(directory, FILE_NAME + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            write(output, DipilotIdentity.keyPair());
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Unable to replace " + target);
        }
        if (!temp.renameTo(target)) {
            throw new IOException("Unable to install " + target);
        }
    }

    static void write(OutputStream output, KeyPair keyPair) throws IOException {
        byte[] privateEncoded = keyPair.getPrivate().getEncoded();
        byte[] publicEncoded = keyPair.getPublic().getEncoded();
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeInt(privateEncoded.length);
        data.write(privateEncoded);
        data.writeInt(publicEncoded.length);
        data.write(publicEncoded);
        data.flush();
    }
}
