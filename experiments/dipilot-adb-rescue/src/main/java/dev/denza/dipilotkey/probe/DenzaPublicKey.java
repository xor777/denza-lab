package dev.denza.dipilotkey.probe;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only the public member of Denza Apps' existing key file; never creates a key. */
final class DenzaPublicKey {
    static final String DUMP = "dumpsys adb";
    static final String PREFIX = "DENZA_PUBLIC_KEY=";
    // The private member is skipped. Only the eight-byte header, four-byte public length,
    // and public DER reach stdout. run-as enforces the installed package's debuggable flag.
    static final String READ_SCRIPT = """
            f=no_backup/adb_auth_key_v1
            if [ -f "$f.bak" ]; then f="$f.bak"; fi
            if [ -f "$f" ]; then
              set -- $(od -An -tu1 -N8 "$f" 2>/dev/null)
              [ "$#" = 8 ] || exit 21
              [ "$1 $2 $3 $4" = '68 65 75 49' ] || exit 22
              n=$(( $5*16777216 + $6*65536 + $7*256 + $8 ))
              [ "$n" -ge 1 ] && [ "$n" -le 16384 ] || exit 23
              p=$((8+n))
              set -- $(od -An -tu1 -j "$p" -N4 "$f" 2>/dev/null)
              [ "$#" = 4 ] || exit 24
              n=$(( $1*16777216 + $2*65536 + $3*256 + $4 ))
              [ "$n" -ge 1 ] && [ "$n" -le 1024 ] || exit 25
              p=$((p+4))
              printf 'DENZA_PUBLIC_KEY='
              dd if="$f" bs=1 skip="$p" count="$n" 2>/dev/null | base64 | tr -d '\\r\\n'
              printf '\\n'
            elif [ -f shared_prefs/adb_auth.xml ]; then
              printf 'DENZA_PUBLIC_KEY='
              sed -n 's/.*<string name="public_x509">\\([^<]*\\)<\\/string>.*/\\1/p' shared_prefs/adb_auth.xml | tr -d '\\r\\n'
              printf '\\n'
            else
              echo DENZA_KEY_NOT_FOUND
              exit 26
            fi
            """;
    static final String READ = "run-as dev.denza.apps sh -c " + quote(READ_SCRIPT);

    final String blob;
    final String payload;
    final String fingerprint;

    private DenzaPublicKey(byte[] bytes) throws Exception {
        blob = Base64.getEncoder().encodeToString(bytes);
        payload = blob + " denza-apps@denza";
        fingerprint = fingerprint(bytes);
    }

    static String fingerprint(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte b : digest) {
            if (text.length() > 0) text.append(':');
            text.append(String.format(Locale.ROOT, "%02X", b & 255));
        }
        return text.toString();
    }

    static DenzaPublicKey read(String output) throws Exception {
        if (output == null) throw new IllegalArgumentException("Публичный ключ не прочитан");
        Matcher marker = Pattern.compile("(?m)^" + PREFIX + "([A-Za-z0-9+/=]+)\\s*$").matcher(output);
        if (!marker.find() || marker.group(1).length() > 1400) {
            throw new IllegalArgumentException("Публичный ключ недоступен через run-as");
        }
        byte[] der = Base64.getDecoder().decode(marker.group(1));
        RSAPublicKey rsa = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
        BigInteger n = rsa.getModulus();
        if (n.bitLength() != 2048 || !n.testBit(0)
                || !rsa.getPublicExponent().equals(BigInteger.valueOf(65537))) {
            throw new IllegalArgumentException("Неожиданный формат RSA-ключа Denza Apps");
        }
        ByteBuffer bytes = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(64);
        bytes.putInt(n.and(BigInteger.valueOf(0xffffffffL))
                .modInverse(BigInteger.ONE.shiftLeft(32)).negate().intValue());
        words(bytes, n);
        words(bytes, BigInteger.ONE.shiftLeft(4096).mod(n));
        bytes.putInt(65537);
        return new DenzaPublicKey(bytes.array());
    }

    private static void words(ByteBuffer output, BigInteger value) {
        for (int i = 0; i < 64; i++) output.putInt(value.shiftRight(i * 32).intValue());
    }

    static String lastFingerprint(String dump) {
        return AdbState.read(dump).fingerprint;
    }

    /** null means the dump is unavailable/incomplete; the XML metadata alone is not trust. */
    Boolean stored(String dump) {
        return AdbState.read(dump).stored(this);
    }

    static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
}
