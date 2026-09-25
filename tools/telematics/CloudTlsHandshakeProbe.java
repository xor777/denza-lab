package dev.denza.tools;

import javax.net.ssl.SSLSocket;

/** One TLS handshake with the production connector. Sends no application frame. */
public final class CloudTlsHandshakeProbe {
    public static void main(String[] args) {
        try {
            OncarTls.factoryIdentity();
            System.out.println("identity_verified=true");
            try (SSLSocket socket = OncarTls.connect("dilinkreg-cn.denzacloud.com", 6001)) {
                System.out.println("tls_connected=true protocol=" + socket.getSession().getProtocol());
            }
        } catch (Throwable failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                String message = String.valueOf(cause.getMessage())
                    .replaceAll("[0-9]{15,}", "[redacted]");
                System.out.println("failure=" + cause.getClass().getName() + " message=" + message);
                for (StackTraceElement frame : cause.getStackTrace()) System.out.println(frame);
            }
        } finally {
            System.out.println("tls_signature_count=" + OncarTls.signatureCount());
            System.exit(0);
        }
    }
}
