package dev.denza.dipilotkey.probe;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Locale;

/**
 * The original BydDipilot 7.32 ADB identity, injected from ignored local build inputs.
 *
 * <p>{@code com.byd.windowmanager.utils.adb.AdbClient}'s static initializer holds this PKCS#8
 * key and the public blob whose comment is {@code wireless@adb}. Connecting with it is that
 * app, so a rescue does not put a second key into the prompt queue.
 */
final class DipilotIdentity {
    static final String COMMENT = "wireless@adb";

    /**
     * MD5 of the ADB public-key blob, the string {@code UsbDebuggingActivity} prints.
     * Computed from the local PKCS#8; the test pins it so an altered local identity cannot pass.
     */
    static final String FINGERPRINT = "2A:FE:35:E4:0C:F8:DB:29:26:E8:87:9E:EC:BD:4E:28";

    /** The start of field {@code m} in that class, the base64 of the ADB public blob. */
    static final String PUBLIC_BLOB_PREFIX = DipilotKeyMaterial.PUBLIC_BLOB_PREFIX;

    private static final int RSA_BITS = 2048;
    private static final int RSA_WORDS = RSA_BITS / 32;

    private static final String PKCS8_BASE64 = DipilotKeyMaterial.PKCS8_BASE64;

    private static KeyPair cached;

    private DipilotIdentity() {
    }

    static synchronized KeyPair keyPair() throws GeneralSecurityException {
        if (cached != null) {
            return cached;
        }
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(PKCS8_BASE64)));
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        cached = new KeyPair(publicKey, privateKey);
        return cached;
    }

    static String fingerprint() throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(androidAdbPublicKey((RSAPublicKey) keyPair().getPublic()));
        StringBuilder text = new StringBuilder(digest.length * 3);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) {
                text.append(':');
            }
            text.append(Character.forDigit((digest[i] >> 4) & 0x0f, 16));
            text.append(Character.forDigit(digest[i] & 0x0f, 16));
        }
        return text.toString().toUpperCase(Locale.US);
    }

    static String androidPublicKeyBase64() throws GeneralSecurityException {
        return Base64.getEncoder().encodeToString(
                androidAdbPublicKey((RSAPublicKey) keyPair().getPublic()));
    }

    /**
     * The same 2048-bit Android ADB public blob {@code AdbKeyStore} builds, so the fingerprint
     * matches what the system dialog shows.
     */
    static byte[] androidAdbPublicKey(RSAPublicKey publicKey) {
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
}
