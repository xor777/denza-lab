/* One TLS 1.2 handshake, with external RSA signing over private stdin/stdout.
 * Default: no application data. --bootstrap-once sends one 69/101/117-byte frame.
 * Research only. No retries, key export, or verification bypass.
 * OpenSSL's deprecated RSA_METHOD interface is deliberately confined to this
 * disposable adapter; a production implementation would use a provider.
 */
#define OPENSSL_SUPPRESS_DEPRECATED
#include <openssl/ssl.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>
#include <openssl/err.h>
#include <openssl/x509v3.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <poll.h>

static SSL *active_ssl;
static int verified_leaf, sign_count, certificate_requested;

static int verify_peer(int ok, X509_STORE_CTX *store) {
    int depth = X509_STORE_CTX_get_error_depth(store);
    if (!ok)
        fprintf(stderr, "{\"event\":\"verify_error\",\"depth\":%d,\"code\":%d}\n",
                depth, X509_STORE_CTX_get_error(store));
    if (ok && depth == 0) verified_leaf = 1;
    return ok; /* Never override OpenSSL verification failures. */
}

static void message(int write_p, int version, int type, const void *buf,
                    size_t len, SSL *ssl, void *arg) {
    (void)version; (void)ssl; (void)arg;
    if (type == SSL3_RT_HANDSHAKE && len) {
        unsigned int kind = ((const unsigned char *)buf)[0];
        if (!write_p && kind == SSL3_MT_CERTIFICATE_REQUEST) certificate_requested = 1;
        fprintf(stderr, "{\"event\":\"handshake_message\",\"out\":%s,\"type\":%u}\n",
                write_p ? "true" : "false", kind);
    } else if (type == SSL3_RT_ALERT && len == 2) {
        const unsigned char *a = buf;
        fprintf(stderr, "{\"event\":\"alert\",\"out\":%s,\"level\":%u,\"description\":%u}\n",
                write_p ? "true" : "false", a[0], a[1]);
    }
}

static int hex_digit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

static int external_sign(int len, const unsigned char *from, unsigned char *to,
                         RSA *rsa, int padding) {
    unsigned char block[256];
    char response[520];
    if (!verified_leaf || !certificate_requested || sign_count ||
        SSL_get_verify_result(active_ssl) != X509_V_OK || RSA_size(rsa) != 256)
        return -1;
    /* SHA-256 PKCS1 DigestInfo is 51 bytes. Decline other algorithms. */
    static const unsigned char prefix[] = {
        0x30,0x31,0x30,0x0d,0x06,0x09,0x60,0x86,0x48,0x01,
        0x65,0x03,0x04,0x02,0x01,0x05,0x00,0x04,0x20};
    if (padding != RSA_PKCS1_PADDING || len != 51 || memcmp(from, prefix, sizeof(prefix)))
        return -1;
    if (!RSA_padding_add_PKCS1_type_1(block, sizeof(block), from, len)) return -1;
    sign_count++;
    fputs("SIGN ", stdout);
    for (size_t i = 0; i < sizeof(block); i++) fprintf(stdout, "%02x", block[i]);
    fputc('\n', stdout); fflush(stdout);
    if (!fgets(response, sizeof(response), stdin) || strlen(response) != 513 || response[512] != '\n')
        return -1;
    for (int i = 0; i < 256; i++) {
        int hi = hex_digit(response[2*i]), lo = hex_digit(response[2*i+1]);
        if (hi < 0 || lo < 0) return -1;
        to[i] = (unsigned char)((hi << 4) | lo);
    }
    OPENSSL_cleanse(block, sizeof(block));
    OPENSSL_cleanse(response, sizeof(response));
    return 256;
}

static X509 *read_cert(const char *path) {
    BIO *bio = BIO_new_file(path, "r");
    if (!bio) return NULL;
    X509 *cert = PEM_read_bio_X509(bio, NULL, NULL, NULL);
    BIO_free(bio);
    return cert;
}

static int bootstrap_once(SSL *ssl) {
    char line[240]; unsigned char packet[117], response[1024];
    fputs("READY\n", stdout); fflush(stdout);
    if (!fgets(line, sizeof(line), stdin)) return 0;
    size_t chars=strlen(line);
    if ((chars != 203 && chars != 139 && chars != 235) || line[chars-1] != '\n') return 0;
    size_t size=(chars-1)/2;
    for (size_t i=0; i<size; i++) {
        int hi=hex_digit(line[i*2]), lo=hex_digit(line[i*2+1]);
        if (hi<0 || lo<0) return 0;
        packet[i]=(unsigned char)((hi<<4)|lo);
    }
    if (packet[0]!=0xfe || packet[1]!=0xfe || packet[2]!=3 || packet[3]!=0 || packet[4]!=size-5) return 0;
    alarm(15);
    int sent=SSL_write(ssl,packet,(int)size);
    OPENSSL_cleanse(line,sizeof(line)); OPENSSL_cleanse(packet,sizeof(packet));
    fprintf(stderr,"{\"event\":\"application_write\",\"bytes\":%d}\n",sent);
    if (sent != (int)size) return 0; /* no write retry */
    size_t have=0, need=5;
    while (have<need) {
        int got=SSL_read(ssl,response+have,(int)(need-have));
        if (got<=0) {
            fprintf(stderr,"{\"event\":\"application_read_end\",\"bytes\":%zu,\"ssl_error\":%d}\n",have,SSL_get_error(ssl,got));
            return 0;
        }
        have+=(size_t)got;
        if (have==5) {
            if (response[0]!=0xfe || response[1]!=0xfe || response[2]!=3) return 0;
            need=5+(((size_t)response[3]<<8)|response[4]);
            if (need<53 || need>sizeof(response)) return 0;
        }
    }
    fputs("DATA ",stdout);
    for (size_t i=0;i<have;i++) fprintf(stdout,"%02x",response[i]);
    fputc('\n',stdout); fflush(stdout);
    fprintf(stderr,"{\"event\":\"application_read\",\"bytes\":%zu}\n",have);
    OPENSSL_cleanse(response,sizeof(response));
    return 1;
}

static int status_once(SSL *ssl) {
    char line[340]; unsigned char packet[165];
    fputs("READY_STATUS\n",stdout); fflush(stdout);
    if (!fgets(line,sizeof(line),stdin) || strlen(line)!=331 || line[330]!='\n') return 0;
    for (size_t i=0;i<sizeof(packet);i++) {
        int hi=hex_digit(line[2*i]),lo=hex_digit(line[2*i+1]);
        if (hi<0 || lo<0) return 0;
        packet[i]=(unsigned char)((hi<<4)|lo);
    }
    static const unsigned char header[]={0xfe,0xfe,3,0,160};
    if (memcmp(packet,header,sizeof(header))) return 0;
    alarm(10);
    int sent=SSL_write(ssl,packet,sizeof(packet));
    OPENSSL_cleanse(line,sizeof(line)); OPENSSL_cleanse(packet,sizeof(packet));
    fprintf(stderr,"{\"event\":\"status_write\",\"bytes\":%d}\n",sent);
    if (sent!=165) return 0;
    /* Stock 512 uses FF (one-way). Hold briefly; do not execute server commands. */
    sleep(5);
    return 1;
}

/* Opaque full-frame relay after the host's original native login handler.
 * Only envelope boundaries are read here, never a vehicle command or field.
 * One inbound frame and one possible reply; idle wait 55s, hard cap 65s.
 */
static int session_once(SSL *ssl) {
    char line[2060]; unsigned char packet[1024];
    fputs("SESSION_READY\n",stdout);fflush(stdout);
    alarm(65);
    if (!fgets(line,sizeof(line),stdin) || strcmp(line,"CONTINUE\n")) return 0;
    if (!SSL_pending(ssl)) {
        struct pollfd fd={.fd=SSL_get_fd(ssl),.events=POLLIN};
        int ready=poll(&fd,1,55000);
        if (ready==0) {
            fputs("{\"event\":\"session_idle_timeout\"}\n",stderr);return 1;
        }
        if (ready<0 || !(fd.revents&POLLIN)) return 0;
    }
    size_t have=0,need=5;
    while (have<need) {
        int got=SSL_read(ssl,packet+have,(int)(need-have));
        if (got<=0) return 0;
        have+=(size_t)got;
        if (have==5) {
            if (packet[0]!=0xfe || packet[1]!=0xfe || packet[2]!=3) return 0;
            need=5+(((size_t)packet[3]<<8)|packet[4]);
            if (need<53 || need>sizeof(packet)) return 0;
        }
    }
    fputs("FRAME ",stdout);
    for (size_t i=0;i<have;i++) fprintf(stdout,"%02x",packet[i]);
    fputc('\n',stdout);fflush(stdout);
    fprintf(stderr,"{\"event\":\"session_read\",\"bytes\":%zu}\n",have);
    if (!fgets(line,sizeof(line),stdin)) return 0;
    size_t chars=strlen(line);
    if (chars<107 || chars>2049 || chars%2!=1 || line[chars-1]!='\n') return 0;
    size_t size=(chars-1)/2;
    for (size_t i=0;i<size;i++) {
        int hi=hex_digit(line[2*i]),lo=hex_digit(line[2*i+1]);
        if (hi<0 || lo<0) return 0;
        packet[i]=(unsigned char)((hi<<4)|lo);
    }
    if (packet[0]!=0xfe || packet[1]!=0xfe || packet[2]!=3 ||
        5+(((size_t)packet[3]<<8)|packet[4])!=size) return 0;
    int sent=SSL_write(ssl,packet,(int)size);
    OPENSSL_cleanse(line,sizeof(line));OPENSSL_cleanse(packet,sizeof(packet));
    fprintf(stderr,"{\"event\":\"session_write\",\"bytes\":%d}\n",sent);
    if (sent!=(int)size) return 0;
    sleep(1);return 1;
}

int main(int argc, char **argv) {
    /* fd, hostname, trusted CA file, client leaf, client issuer (or '-'). */
    if (argc != 6 && !(argc == 7 && (!strcmp(argv[6], "--bootstrap-once") || !strcmp(argv[6], "--status-once") || !strcmp(argv[6], "--session-once")))) return 64;
    signal(SIGPIPE, SIG_IGN);
    alarm(35);
    SSL_CTX *ctx = SSL_CTX_new(TLS_client_method());
    X509 *leaf = NULL, *issuer = NULL;
    EVP_PKEY *pub = NULL, *key = NULL;
    RSA *rsa = NULL;
    RSA_METHOD *method = NULL;
    int result = 1;
    if (!ctx || !SSL_CTX_set_min_proto_version(ctx, TLS1_2_VERSION) ||
        !SSL_CTX_set_max_proto_version(ctx, TLS1_2_VERSION) ||
        !SSL_CTX_set_cipher_list(ctx, "ECDHE-RSA-AES128-GCM-SHA256") ||
        !SSL_CTX_set1_sigalgs_list(ctx, "rsa_pkcs1_sha256") ||
        !SSL_CTX_load_verify_locations(ctx, argv[3], NULL)) goto done;
    SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, verify_peer);
    SSL_CTX_set_options(ctx, SSL_OP_NO_RENEGOTIATION | SSL_OP_NO_TICKET);
    SSL_CTX_set_session_cache_mode(ctx, SSL_SESS_CACHE_OFF);
    SSL_CTX_set_msg_callback(ctx, message);
    leaf = read_cert(argv[4]);
    if (!leaf || !(pub = X509_get_pubkey(leaf)) || !(rsa = EVP_PKEY_get1_RSA(pub)) ||
        RSA_size(rsa) != 256) goto done;
    method = RSA_meth_dup(RSA_get_default_method());
    if (!method || !RSA_meth_set1_name(method, "owner-approved-binder-signature") ||
        !RSA_meth_set_priv_enc(method, external_sign) || !RSA_set_method(rsa, method)) goto done;
    RSA_set_flags(rsa, RSA_FLAG_EXT_PKEY);
    key = EVP_PKEY_new();
    if (!key || !EVP_PKEY_set1_RSA(key, rsa) || !SSL_CTX_use_certificate(ctx, leaf) ||
        !SSL_CTX_use_PrivateKey(ctx, key) || !SSL_CTX_check_private_key(ctx)) goto done;
    if (strcmp(argv[5], "-")) {
        issuer = read_cert(argv[5]);
        if (!issuer || !SSL_CTX_add1_chain_cert(ctx, issuer)) goto done;
    }
    active_ssl = SSL_new(ctx);
    if (!active_ssl || !SSL_set1_host(active_ssl, argv[2]) ||
        !SSL_set_tlsext_host_name(active_ssl, argv[2]) ||
        !SSL_set_fd(active_ssl, atoi(argv[1]))) goto done;
    X509_VERIFY_PARAM_set_hostflags(SSL_get0_param(active_ssl), X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS);
    int connected = SSL_connect(active_ssl);
    int ssl_error = connected == 1 ? 0 : SSL_get_error(active_ssl, connected);
    fprintf(stderr, "{\"event\":\"result\",\"handshake_complete\":%s,"
            "\"verify_result\":%ld,\"ssl_error\":%d,\"sign_count\":%d,"
            "\"certificate_requested\":%s,\"application_bytes_sent\":0}\n",
            connected == 1 ? "true" : "false", SSL_get_verify_result(active_ssl),
            ssl_error, sign_count, certificate_requested ? "true" : "false");
    X509 *peer = SSL_get1_peer_certificate(active_ssl);
    if (peer) {
        unsigned char digest[EVP_MAX_MD_SIZE]; unsigned int size;
        if (X509_digest(peer, EVP_sha256(), digest, &size)) {
            fputs("{\"event\":\"server_certificate\",\"sha256\":\"", stderr);
            for (unsigned int i=0;i<size;i++) fprintf(stderr, "%02x", digest[i]);
            fputs("\"}\n", stderr);
        }
        X509_free(peer);
    }
    if (connected == 1 && sign_count == 1 && verified_leaf) {
        fprintf(stderr, "{\"event\":\"negotiated\",\"version\":\"%s\",\"cipher\":\"%s\"}\n",
                SSL_get_version(active_ssl), SSL_get_cipher_name(active_ssl));
        result = argc == 7 ? !bootstrap_once(active_ssl) : 0;
        if (!result && argc == 7 && !strcmp(argv[6], "--status-once")) result = !status_once(active_ssl);
        if (!result && argc == 7 && !strcmp(argv[6], "--session-once")) result = !session_once(active_ssl);
        SSL_shutdown(active_ssl); /* Single close_notify; no read/retry loop. */
    }
done:
    if (result) {
        unsigned long error;
        while ((error = ERR_get_error()))
            fprintf(stderr, "{\"event\":\"openssl_error\",\"code\":%lu,\"reason\":\"%s\"}\n",
                    error, ERR_reason_error_string(error) ? ERR_reason_error_string(error) : "unknown");
    }
    SSL_free(active_ssl); SSL_CTX_free(ctx);
    X509_free(leaf); X509_free(issuer); EVP_PKEY_free(pub); EVP_PKEY_free(key);
    RSA_free(rsa); RSA_meth_free(method);
    return result;
}
