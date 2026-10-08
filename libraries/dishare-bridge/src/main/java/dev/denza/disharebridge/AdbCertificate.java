package dev.denza.disharebridge;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** A certificate envelope for the existing ADB key. No provider is registered globally. */
final class AdbCertificate {
    private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();

    static synchronized X509Certificate load(File directory, KeyPair key) throws Exception {
        AtomicFile file = new AtomicFile(new File(directory, "adb_tls_certificate.der"));
        try (FileInputStream input = file.openRead()) {
            X509Certificate certificate = (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(input);
            certificate.checkValidity();
            certificate.verify(key.getPublic());
            if (Arrays.equals(certificate.getPublicKey().getEncoded(), key.getPublic().getEncoded())) {
                return certificate;
            }
        } catch (Exception ignored) {
            // Missing, expired or stale envelope: the identity itself is never replaced here.
        }
        X509Certificate certificate = create(key, System.currentTimeMillis());
        FileOutputStream output = file.startWrite();
        try {
            output.write(certificate.getEncoded());
            file.finishWrite(output);
        } catch (Exception error) {
            file.failWrite(output);
            throw error;
        }
        return certificate;
    }

    static X509Certificate create(KeyPair key, long now) throws GeneralSecurityException {
        try {
            X500Name name = new X500Name("CN=adb");
            return new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(
                    new JcaX509v3CertificateBuilder(name,
                            new BigInteger(128, new SecureRandom()).add(BigInteger.ONE),
                            new Date(now - 86_400_000L), new Date(now + 315_360_000_000L),
                            name, key.getPublic()).build(
                            new JcaContentSignerBuilder("SHA256withRSA").setProvider(PROVIDER)
                                    .build(key.getPrivate())));
        } catch (Exception error) {
            throw new GeneralSecurityException("Unable to wrap ADB identity for TLS", error);
        }
    }
}
