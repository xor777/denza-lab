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
 * The one ADB identity baked into BydDipilot 7.32.
 *
 * <p>{@code com.byd.windowmanager.utils.adb.AdbClient}'s static initializer holds this PKCS#8
 * key and the public blob whose comment is {@code wireless@adb}. Connecting with it is that
 * app, so a rescue does not put a second key into the prompt queue.
 */
final class DipilotIdentity {
    static final String COMMENT = "wireless@adb";

    /**
     * MD5 of the ADB public-key blob, the string {@code UsbDebuggingActivity} prints.
     * Computed from the PKCS#8 above; the test pins it so a corrupted constant cannot pass.
     */
    static final String FINGERPRINT = "2A:FE:35:E4:0C:F8:DB:29:26:E8:87:9E:EC:BD:4E:28";

    /** The start of field {@code m} in that class, the base64 of the ADB public blob. */
    static final String PUBLIC_BLOB_PREFIX = "QAAAANPLPjylOx9skyshGEm0VjuV3Wp69lNdIi0x";

    private static final int RSA_BITS = 2048;
    private static final int RSA_WORDS = RSA_BITS / 32;

    private static final String PKCS8_BASE64 =
            "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCf0EeNJDNT0AP+oH3agDVgu6Dp5t2E"
                    + "qGewaLWOJKobTUCie9iBIr3Ppdv3INt6Wh9wmvmcj0rcjOCUtGqo+q0mcrPrDKijlyot6I8QpfE02jQ"
                    + "C5ybTD02qDpqzBSNU8sBYKvpYm+71CDhbEcJskwJN9u19Bv+jgd4uwIIaHXseTsq+r3CEQPLd3wPKNb"
                    + "ziTfyvW8HnvD6EbZ1trzEyWmHp1bNssqQMsKzkn37tdK9YD9Z1G4+qCvZ+vWE6hU8Woq1pAN6vjcip9"
                    + "1zNhW298JSXDRUT6bSSowTB69sxk8M1HcdR6F/1pD62b4MxLSJdU/Z6at2VO1a0SRghK5NsHzulAgMB"
                    + "AAECggEAALuPpDldegSIPLo/G78GGw0dWRj6f5+ljCR4RcEBUx2HAPk3wgwum343SRBNbXoA0bSZ5OC"
                    + "W0g6lSEPpGSzLUKkGAeXhXRbS0v1LQs27ADFi2hXLD7ONVKtf2gtZV87zecrb4ukDh0UNNgrQigM4tr"
                    + "69s9etfYqHF3MQcEuv+JhguqB72fBSTxWi2gynQZGYSsV48HKs8q982forgWTNjtaaVj4x8gSQ0/FTf"
                    + "anSpV0uap4xoXIc9jDYHdE8NEiQFzwhjsN+iHUke9YKNuudNJcKXeQ4+tkk2RvQvlKMy0F6bvRtqBag"
                    + "VdCojs+y+R2LG5hs6DS+HBHTkpjJr8PqCwKBgQDfOBAEDSdlOiyQZUbdQkq7bmz1HLEMvRmkZTi8V7w"
                    + "X3UEzFutxgYJHFL+156BpXL32LaiZiDgAqNrWgUTpRyfcsPaOgrUM5MzYGpRh+Qoc6XdnEJMUdmrQfk"
                    + "zioOoZHyLKfltqMB7rnuHEcOsgdd/Nvq+d90BBbiK9e99HQrBBAwKBgQC3SHw+mgyAKvnlAUJl4H1W"
                    + "kT8O1XmK0HipF5QXDdf3I1jPYeHlk5IOeElF0PVCjmzST2iv9mz0pLE/KNBQjeNMZc+oc69HLpbhRBL"
                    + "xWVKxMzUmrSovYGuBMyvJUjvFzQpTWJaFjPahh8k7qamX4XR/E+PhiU17rghPSiBBtZxsNwKBgQClbs"
                    + "xX5FPNLxc2EQk7FFWEoet4ocIJ+eAWObqZw8AwQ5d9wL/QCLa/7X3D2B3H2Kck22P4Hb+7pWlCzt1+s"
                    + "nC1nCWvKun521yB0Pklv0eic9k9dkg7QyQYz3I2CCJXaf8D6i/f/Df+UmtqhnGMRjAPMQLA5S0nEPQI"
                    + "UNUC4Eb2dwKBgCP+IhgRHH9W8TgdpTJogXSslVuOZI30Hp3mOXjFiTHJSTmAmWICNt6MlKcXP/LQEWL"
                    + "DoWVc2Uy5wu0KUM5g7tAj9kG4ZiVuasbWHXoz9l9CFSABRZWEM9hteh3Q/lf7yhlP/k8/vXc/OS90RA"
                    + "/FgKEsAMZbtlGhfZRElE0TIyLFAoGBAJxWaYQQP7M3YWS07V2GyYgmfYFe3klp2DvwZIjLkTpHk0cMy"
                    + "Il0mA/PqxO7nb/5S5htfmDK65Gxo5jQH2uWUYrHxICMcAPWsUTC/dwK7q/HIHU21TkKgYhgZZ/Tcim1"
                    + "RSCmVvkZfCuejBc2bldQ00tB0M3tod4sB/kWaxGHYNng";

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
