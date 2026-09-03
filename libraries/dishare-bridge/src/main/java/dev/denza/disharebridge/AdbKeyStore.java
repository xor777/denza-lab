package dev.denza.disharebridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

final class AdbKeyStore {
    private static final String PREFS_NAME = "adb_auth";
    private static final String KEY_PRIVATE = "private_pkcs8";
    private static final String KEY_PUBLIC = "public_x509";
    private static final String DEFAULT_PUBLIC_KEY_COMMENT = "denza@local-adb";
    private static final String KEY_FILE_NAME = "adb_auth_key_v1";
    private static final String LOCK_FILE_NAME = "adb_auth_key.lock";
    private static final int KEY_FILE_MAGIC = 0x44414b31;
    private static final int MAX_ENCODED_KEY_BYTES = 16 * 1024;
    private static final int RSA_BITS = 2048;
    private static final int RSA_BYTES = RSA_BITS / 8;
    private static final int RSA_WORDS = RSA_BITS / 32;
    private static final Object KEY_PAIR_LOCK = new Object();
    private static final byte[] SHA1_DIGEST_INFO_PREFIX = new byte[] {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final Context context;
    private final String publicKeyComment;
    private KeyPair cachedKeyPair;

    AdbKeyStore(Context context, String publicKeyComment) {
        this.context = context.getApplicationContext();
        this.publicKeyComment = normalizeComment(publicKeyComment);
    }

    synchronized byte[] signToken(byte[] token) throws GeneralSecurityException {
        if (token.length != 20) {
            throw new GeneralSecurityException("Unexpected ADB auth token length " + token.length);
        }
        PrivateKey privateKey = keyPair().getPrivate();
        PublicKey publicKey = keyPair().getPublic();
        if (!(privateKey instanceof RSAPrivateKey) || !(publicKey instanceof RSAPublicKey)) {
            throw new GeneralSecurityException("ADB key is not RSA");
        }

        byte[] digestInfo = new byte[SHA1_DIGEST_INFO_PREFIX.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, digestInfo, 0,
                SHA1_DIGEST_INFO_PREFIX.length);
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO_PREFIX.length, token.length);

        byte[] block = new byte[RSA_BYTES];
        block[0] = 0x00;
        block[1] = 0x01;
        Arrays.fill(block, 2, RSA_BYTES - digestInfo.length - 1, (byte) 0xff);
        block[RSA_BYTES - digestInfo.length - 1] = 0x00;
        System.arraycopy(digestInfo, 0, block, RSA_BYTES - digestInfo.length,
                digestInfo.length);

        RSAPrivateKey rsaPrivateKey = (RSAPrivateKey) privateKey;
        BigInteger signature = new BigInteger(1, block)
                .modPow(rsaPrivateKey.getPrivateExponent(), rsaPrivateKey.getModulus());
        return fixedLength(signature, RSA_BYTES);
    }

    synchronized byte[] publicKeyPayload() throws GeneralSecurityException {
        PublicKey publicKey = keyPair().getPublic();
        if (!(publicKey instanceof RSAPublicKey)) {
            throw new GeneralSecurityException("ADB public key is not RSA");
        }
        String key = Base64.encodeToString(androidAdbPublicKey((RSAPublicKey) publicKey),
                Base64.NO_WRAP) + " " + publicKeyComment;
        byte[] ascii = key.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[ascii.length + 1];
        System.arraycopy(ascii, 0, payload, 0, ascii.length);
        payload[payload.length - 1] = 0;
        return payload;
    }

    /**
     * The key as Android names it in the "Allow USB debugging?" dialog.
     *
     * <p>MD5 over the decoded ADB public-key blob, uppercase hex, colon separated - the exact
     * formatting of {@code UsbDebuggingActivity.getFingerprints}. It exists so a person looking at
     * a prompt on the vehicle can tell <em>whose</em> key they are approving, which matters as soon
     * as more than one of this project's apps has an identity of its own.
     */
    synchronized String publicKeyFingerprint() throws GeneralSecurityException {
        PublicKey publicKey = keyPair().getPublic();
        if (!(publicKey instanceof RSAPublicKey)) {
            throw new GeneralSecurityException("ADB public key is not RSA");
        }
        return formatFingerprint(
                MessageDigest.getInstance("MD5")
                        .digest(androidAdbPublicKey((RSAPublicKey) publicKey)));
    }

    static String formatFingerprint(byte[] digest) {
        StringBuilder text = new StringBuilder(digest.length * 3);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                text.append(':');
            }
            text.append(Character.forDigit((digest[i] >> 4) & 0x0f, 16));
            text.append(Character.forDigit(digest[i] & 0x0f, 16));
        }
        return text.toString().toUpperCase(java.util.Locale.US);
    }

    private KeyPair keyPair() throws GeneralSecurityException {
        synchronized (KEY_PAIR_LOCK) {
            if (cachedKeyPair != null) {
                return cachedKeyPair;
            }
            File storageDirectory = context.getNoBackupFilesDir();
            if (!storageDirectory.isDirectory() && !storageDirectory.mkdirs()) {
                throw new GeneralSecurityException("Unable to create ADB key directory");
            }
            File lockFile = new File(storageDirectory, LOCK_FILE_NAME);
            try (RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
                    FileChannel lockChannel = lockAccess.getChannel();
                    FileLock ignored = lockChannel.lock()) {
                AtomicFile keyFile = new AtomicFile(new File(storageDirectory, KEY_FILE_NAME));
                if (keyFile.getBaseFile().isFile()) {
                    try (FileInputStream input = keyFile.openRead()) {
                        cachedKeyPair = readKeyPair(input);
                        return cachedKeyPair;
                    }
                }

                SharedPreferences prefs = context.getSharedPreferences(
                        PREFS_NAME, Context.MODE_PRIVATE);
                String privateEncoded = prefs.getString(KEY_PRIVATE, null);
                String publicEncoded = prefs.getString(KEY_PUBLIC, null);
                if ((privateEncoded == null) != (publicEncoded == null)) {
                    throw new GeneralSecurityException("Incomplete persisted ADB key pair");
                }
                if (privateEncoded != null) {
                    cachedKeyPair = decodeLegacyKeyPair(privateEncoded, publicEncoded);
                    writeKeyPair(keyFile, cachedKeyPair);
                    return cachedKeyPair;
                }

                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(RSA_BITS);
                KeyPair generated = generator.generateKeyPair();
                writeKeyPair(keyFile, generated);
                prefs.edit()
                        .putString(KEY_PRIVATE, Base64.encodeToString(
                                generated.getPrivate().getEncoded(), Base64.NO_WRAP))
                        .putString(KEY_PUBLIC, Base64.encodeToString(
                                generated.getPublic().getEncoded(), Base64.NO_WRAP))
                        .commit();
                cachedKeyPair = generated;
                return cachedKeyPair;
            } catch (IOException error) {
                throw new GeneralSecurityException("Unable to load the ADB key pair", error);
            }
        }
    }

    private static KeyPair decodeLegacyKeyPair(String privateEncoded, String publicEncoded)
            throws GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                Base64.decode(privateEncoded, Base64.NO_WRAP)));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                Base64.decode(publicEncoded, Base64.NO_WRAP)));
        return new KeyPair(publicKey, privateKey);
    }

    private static void writeKeyPair(AtomicFile keyFile, KeyPair keyPair)
            throws GeneralSecurityException {
        FileOutputStream rawOutput = null;
        try {
            rawOutput = keyFile.startWrite();
            writeKeyPair(rawOutput, keyPair);
            keyFile.finishWrite(rawOutput);
            rawOutput = null;
        } catch (IOException error) {
            if (rawOutput != null) {
                keyFile.failWrite(rawOutput);
            }
            throw new GeneralSecurityException("Unable to persist the ADB key pair", error);
        }
    }

    static void writeKeyPair(OutputStream output, KeyPair keyPair) throws IOException {
        byte[] privateEncoded = keyPair.getPrivate().getEncoded();
        byte[] publicEncoded = keyPair.getPublic().getEncoded();
        DataOutputStream data = new DataOutputStream(new BufferedOutputStream(output));
        data.writeInt(KEY_FILE_MAGIC);
        data.writeInt(privateEncoded.length);
        data.write(privateEncoded);
        data.writeInt(publicEncoded.length);
        data.write(publicEncoded);
        data.flush();
    }

    static KeyPair readKeyPair(InputStream input) throws IOException, GeneralSecurityException {
        DataInputStream data = new DataInputStream(new BufferedInputStream(input));
        if (data.readInt() != KEY_FILE_MAGIC) {
            throw new GeneralSecurityException("Unrecognized ADB key file");
        }
        byte[] privateEncoded = readEncodedKey(data, "private");
        byte[] publicEncoded = readEncodedKey(data, "public");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateEncoded));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicEncoded));
        return new KeyPair(publicKey, privateKey);
    }

    private static byte[] readEncodedKey(DataInputStream input, String label) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_ENCODED_KEY_BYTES) {
            throw new IOException("Invalid encoded ADB " + label + " key length " + length);
        }
        byte[] encoded = new byte[length];
        input.readFully(encoded);
        return encoded;
    }

    private static byte[] androidAdbPublicKey(RSAPublicKey publicKey) {
        BigInteger modulus = publicKey.getModulus();
        BigInteger exponent = publicKey.getPublicExponent();
        BigInteger r = BigInteger.ONE.shiftLeft(RSA_BITS);
        BigInteger rr = r.multiply(r).mod(modulus);
        long n0 = modulus.and(BigInteger.valueOf(0xffffffffL)).longValue();
        long n0inv = BigInteger.valueOf(n0).modInverse(BigInteger.ONE.shiftLeft(32)).longValue();
        long n0invNegated = (-n0inv) & 0xffffffffL;

        ByteBuffer buffer = ByteBuffer.allocate((2 + RSA_WORDS + RSA_WORDS + 1) * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        putUInt32(buffer, RSA_WORDS);
        putUInt32(buffer, n0invNegated);
        putLittleEndianWords(buffer, modulus);
        putLittleEndianWords(buffer, rr);
        putUInt32(buffer, exponent.longValue());
        return buffer.array();
    }

    private static void putLittleEndianWords(ByteBuffer buffer, BigInteger value) {
        for (int i = 0; i < RSA_WORDS; i++) {
            BigInteger word = value.shiftRight(i * 32).and(BigInteger.valueOf(0xffffffffL));
            putUInt32(buffer, word.longValue());
        }
    }

    private static void putUInt32(ByteBuffer buffer, long value) {
        buffer.putInt((int) (value & 0xffffffffL));
    }

    private static byte[] fixedLength(BigInteger value, int length) {
        byte[] encoded = value.toByteArray();
        if (encoded.length == length) {
            return encoded;
        }
        byte[] fixed = new byte[length];
        int copyLength = Math.min(encoded.length, length);
        System.arraycopy(encoded, encoded.length - copyLength, fixed, length - copyLength,
                copyLength);
        return fixed;
    }

    private static String normalizeComment(String publicKeyComment) {
        if (publicKeyComment == null || publicKeyComment.trim().isEmpty()) {
            return DEFAULT_PUBLIC_KEY_COMMENT;
        }
        return publicKeyComment.trim()
                .replace('\n', '_')
                .replace('\r', '_')
                .replace('\t', '_')
                .replace(' ', '_');
    }
}
