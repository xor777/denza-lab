// One fixed OEM metadata request. Identity arrives on stdin and is never saved.
import https from 'node:https';
import tls from 'node:tls';
import { X509Certificate } from 'node:crypto';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const methods = new Set(['getEcuList', 'checkVersion']);
const limit = 1024 * 1024;
let finished = false;
let stage = 'stdin', requestStarted = false, tlsEstablished = false;
const timer = setTimeout(() => finish({ error: 'total_timeout' }), 12000);
function finish(value) {
  if (finished) return;
  finished = true;
  clearTimeout(timer);
  process.stdout.write(JSON.stringify({ ...value, stage, requestStarted, tlsEstablished }), () => process.exit(value.error ? 1 : 0));
}
try {
  const parts = [];
  let size = 0;
  for await (const chunk of process.stdin) {
    size += chunk.length;
    if (size > 512 * 1024) throw new Error('input_too_large');
    parts.push(chunk);
  }
  stage = 'parse_input';
  const input = JSON.parse(Buffer.concat(parts).toString('utf8'));
  if (!methods.has(input.method) || typeof input.token !== 'string' ||
      !/^[\x21-\x7e]{1,8192}$/.test(input.token)) throw new Error('invalid_input');
  const body = Buffer.from(JSON.stringify(input.body));
  if (body.length > 256 * 1024) throw new Error('body_too_large');
  stage = 'load_ca';
  const root = fileURLToPath(new URL('../', import.meta.url));
  const ca = fs.readFileSync(root + 'captures/adas-speed-20261018/reports/ads-acquisition/ota-public-ca.pem');
  stage = 'tls_setup';
  const legacyIdentity = new X509Certificate(input.cert).fingerprint256.replaceAll(':', '').toLowerCase() ===
    '5baa0eafbb48b3d1e50259fe1053ff9974b61d557fc6798e5d16d36993ad69f0';
  // The exact owner certificate is RSA-2048/SHA-1. OpenSSL rejects its issuer
  // signature at levels 1/2 before connecting. Restrict compatibility to that
  // identity, TLS 1.2 + ECDHE/AES-GCM, while also pinning the SHA-256 server leaf
  // independently verified at normal security level (server-pin.json).
  const secureContext = tls.createSecureContext({ cert: input.cert, key: input.key, ca,
    allowPartialTrustChain: true, minVersion: 'TLSv1.2', maxVersion: 'TLSv1.2',
    ...(legacyIdentity ? { ciphers: 'ECDHE-RSA-AES256-GCM-SHA384:ECDHE-RSA-AES128-GCM-SHA256:@SECLEVEL=0' } : {}) });
  if (process.argv.includes('--validate-only')) {
    finish({ validated: true });
  } else {
  stage = 'request';
  const request = https.request({
    hostname: 'fota-tsp.denzacloud.com', port: 6113,
    path: '/ota/otaService/' + input.method, method: 'POST', agent: false,
    secureContext, rejectUnauthorized: true,
    checkServerIdentity(host, certificate) {
      const nameError = tls.checkServerIdentity(host, certificate);
      if (nameError) return nameError;
      if (certificate.fingerprint256.replaceAll(':', '').toLowerCase() !==
          '767466d51dd35fda1940e47e5b9399788ab81b530c3e4a50e66e18308cd73406') {
        const error = new Error('server_identity_mismatch');
        error.code = 'ERR_OTA_PIN_MISMATCH';
        return error;
      }
    },
    headers: { 'User-Agent': 'BYD', 'Content-Type': 'application/json;charset=utf-8',
      Authorization: 'basic' + input.token, 'Content-Length': body.length,
      Connection: 'close', 'Cache-Control': 'no-store' },
  }, response => {
    stage = 'response';
    const chunks = [];
    let bytes = 0;
    response.on('data', chunk => {
      bytes += chunk.length;
      if (bytes > limit) { response.destroy(); finish({ error: 'response_too_large' }); }
      else chunks.push(chunk);
    });
    response.on('end', () => finish({ httpStatus: response.statusCode,
      body: Buffer.concat(chunks).toString('base64') }));
    response.on('error', () => finish({ error: 'response_error' }));
  });
  request.on('error', error => finish({ error: 'https_error',
    code: /^[A-Z0-9_]{1,80}$/.test(error.code || '') ? error.code : 'UNKNOWN' }));
  request.on('socket', socket => socket.once('secureConnect', () => { tlsEstablished = true; }));
  request.end(body);
  requestStarted = true;
  }
} catch (error) {
  finish({ error: 'local_input_or_tls_setup_error',
    code: /^[A-Z0-9_]{1,80}$/.test(error.code || '') ? error.code : 'UNKNOWN' });
}
