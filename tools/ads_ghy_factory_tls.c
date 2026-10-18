/* One TLS 1.2 handshake, with external RSA signing over private stdin/stdout.
 * Derived from tools/telematics/tls_identity_client.c. Fixed GHY getEcuList only.
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

static SSL *active_ssl;
static int verified_leaf, sign_count, certificate_requested, require_ghy_pin;

static int verify_peer(int ok, X509_STORE_CTX *store) {
    int depth = X509_STORE_CTX_get_error_depth(store);
    if (!ok)
        fprintf(stderr, "{\"event\":\"verify_error\",\"depth\":%d,\"code\":%d}\n",
                depth, X509_STORE_CTX_get_error(store));
    if (ok && depth == 0 && require_ghy_pin) {
        unsigned char digest[32]; unsigned int size = 0; char text[65];
        if (!X509_digest(X509_STORE_CTX_get_current_cert(store), EVP_sha256(), digest, &size) || size != 32) return 0;
        for (int i=0; i<32; i++) snprintf(text+2*i, 3, "%02x", digest[i]);
        if (strcmp(text, "47a599ff34cd17f60a253a94d2cd5dac84140c799ef955176970c5ffc406e40b")) return 0;
    }
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

/* Only three bounded fields cross the pipe; no caller-controlled URL/method. */
static int field(char *value, size_t capacity, int restricted, int empty) {
    if (!fgets(value, (int)capacity, stdin)) return 0;
    size_t n = strlen(value);
    if (!n || value[n-1] != '\n') return 0;
    value[--n] = 0;
    if (!n && !empty) return 0;
    for (size_t i=0; i<n; i++) {
        unsigned char c = (unsigned char)value[i];
        if (c < 0x21 || c > 0x7e) return 0;
        if (restricted && !((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='.'||c=='_'||c=='-')) return 0;
    }
    return 1;
}
static int http_once(SSL *ssl) {
    char token[8194], version[66], uuid[258], body[384], request[10000];
    fputs("READY_HTTP\n", stdout); fflush(stdout);
    if (!field(token,sizeof(token),0,0) || !field(version,sizeof(version),1,0) || !field(uuid,sizeof(uuid),1,1)) return 0;
    int n = snprintf(body,sizeof(body),"{\"version\":\"%s\",\"uuid\":\"%s\"}",version,uuid);
    if (n<0 || (size_t)n>=sizeof(body)) return 0;
    int size = snprintf(request,sizeof(request),
        "POST /client-service/fota/api/v1/service/getEcuList HTTP/1.1\r\n"
        "Host: api-ota-cn.denzacloud.com\r\nUser-Agent: BYD\r\n"
        "Authorization: basic%s\r\nContent-Type: application/json;charset=utf-8\r\n"
        "Content-Length: %d\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n%s",token,n,body);
    OPENSSL_cleanse(token,sizeof(token)); OPENSSL_cleanse(uuid,sizeof(uuid)); OPENSSL_cleanse(body,sizeof(body));
    if (size<0 || (size_t)size>=sizeof(request)) return 0;
    alarm(15);
    int sent = SSL_write(ssl,request,size);
    OPENSSL_cleanse(request,sizeof(request));
    fprintf(stderr,"{\"event\":\"application_write\",\"bytes\":%d}\n",sent);
    if (sent!=size) return 0;
    const size_t cap = 1024*1024+16384;
    unsigned char *response = malloc(cap); size_t have=0;
    if (!response) return 0;
    int complete=0;
    while (have<cap) {
        int got=SSL_read(ssl,response+have,(int)(cap-have));
        if (got<=0) { complete=1; break; }
        have+=(size_t)got;
    }
    if (complete && have) {
        fputs("HTTP_DATA ",stdout);
        for (size_t i=0;i<have;i++) fprintf(stdout,"%02x",response[i]);
        fputc('\n',stdout); fflush(stdout);
        fprintf(stderr,"{\"event\":\"application_read\",\"bytes\":%zu}\n",have);
    }
    OPENSSL_cleanse(response,cap); free(response);
    /* Python also requires complete HTTP framing before accepting a response. */
    return complete && have;
}

int main(int argc, char **argv) {
    /* fd, hostname, trusted CA file, client leaf, client issuer (or '-'). */
    if (argc != 6) return 64;
    require_ghy_pin = !strcmp(argv[2], "api-ota-cn.denzacloud.com");
    if (!require_ghy_pin && strcmp(argv[2], "localhost") && strcmp(argv[2], "wrong.invalid")) return 64;
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
        result = !http_once(active_ssl);
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
