package dev.denza.disharebridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
    private static final int RSA_BITS = 2048;
    private static final int RSA_BYTES = RSA_BITS / 8;
    private static final int RSA_WORDS = RSA_BITS / 32;
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

    private KeyPair keyPair() throws GeneralSecurityException {
        if (cachedKeyPair != null) {
            return cachedKeyPair;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String privateEncoded = prefs.getString(KEY_PRIVATE, null);
        String publicEncoded = prefs.getString(KEY_PUBLIC, null);
        if (privateEncoded != null && publicEncoded != null) {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.decode(privateEncoded, Base64.NO_WRAP)));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                    Base64.decode(publicEncoded, Base64.NO_WRAP)));
            cachedKeyPair = new KeyPair(publicKey, privateKey);
            return cachedKeyPair;
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_BITS);
        cachedKeyPair = generator.generateKeyPair();
        prefs.edit()
                .putString(KEY_PRIVATE, Base64.encodeToString(
                        cachedKeyPair.getPrivate().getEncoded(), Base64.NO_WRAP))
                .putString(KEY_PUBLIC, Base64.encodeToString(
                        cachedKeyPair.getPublic().getEncoded(), Base64.NO_WRAP))
                .apply();
        return cachedKeyPair;
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
