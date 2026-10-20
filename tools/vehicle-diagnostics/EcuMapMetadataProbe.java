import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import javax.xml.parsers.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Prepared offline. Never run without separate approval and the owning host gate.
 * No BYD/Android classes, Binder, chip, filesystem writes, redirects or retries.
 * Private inputs are RAM-only; output consists solely of fixed states and projection.
 */
public final class EcuMapMetadataProbe {
    static final String HOST = "fota-tsp.denzacloud.com";
    static final String URI = "/ota/otaService/getEcuList";
    static final int PORT = 6113, EXPORT_PORT = 46321, MAGIC = 0x42594446;
    static final int MAX_BODY = 2 * 1024 * 1024, MAX_JSON = 4096;
    static final String[] PATHS = {
        "/data/user/0/com.byd.otaupdate/files/dcrt.crt",
        "/data/user/0/com.byd.otaupdate/files/dkey.key",
        "/data/user/0/com.byd.otaupdate/shared_prefs/share_data_prefs.xml",
        "/collect2/SourceVehicleVersion2"
    };
    static final int[] LIMITS = {32768, 16384, 1024 * 1024, 256};
    static final List<String> FIELDS = Collections.unmodifiableList(Arrays.asList(
        "ecuId", "ecuDiaId", "ecuReprogramType", "ecuChannel", "networkNumber",
        "logicAddress", "ecuDId", "ecuHardDid", "ecuConfigDid", "seq"));
    // Public CA DER, verbatim matched OTA assets; provenance in adjacent preparation report.
    static final String[] TRUST_DER = {"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"};
    static volatile Socket active;
    static volatile boolean finished;
    static volatile String stage = "start";
    static final String[] INPUT_STAGES = {"input_cert", "input_key", "input_prefs", "input_version"};

    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    static void require(boolean value, String code) throws Failure {
        if (!value) throw new Failure(code);
    }
    static long deadline(int ms) { return System.nanoTime() + ms * 1000000L; }
    static int remaining(long end) throws Failure {
        long ns = end - System.nanoTime();
        require(ns > 0, "deadline");
        return (int)Math.min(Integer.MAX_VALUE, Math.max(1L, (ns + 999999L) / 1000000L));
    }
    static void closeActive() {
        Socket s = active;
        if (s != null) try { s.close(); } catch (Throwable ignored) { }
    }
    static void watchdog(int durationMs) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(durationMs - 200); } catch (InterruptedException ignored) { return; }
            if (finished) return;
            // TLS close may itself block; halt is independent of that close attempt.
            Thread closer = new Thread(EcuMapMetadataProbe::closeActive, "probe-close");
            closer.setDaemon(true); closer.start();
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            Runtime.getRuntime().halt(91);
        }, "probe-deadline");
        t.setDaemon(true); t.start();
    }
    static void silenceRuntimeOutput() {
        // This car's CtaAdapter writes to System.out on every Socket.connect.
        // Keep runtime/provider output off the private protocol channel; do not
        // forward it to stderr, which ADB may merge into the same host pipe.
        PrintStream discard = new PrintStream(new OutputStream() {
            public void write(int value) { }
            public void write(byte[] value, int offset, int length) { }
        });
        System.setOut(discard);
        System.setErr(discard);
    }
    public static void main(String[] args) {
        final PrintStream protocol = System.out;
        try {
            silenceRuntimeOutput();
            watchdog(45000);
            androidShellGuard();
            require(args.length == 2 && "--nonce".equals(args[0]) &&
                args[1].matches("[0-9a-f]{32}"), "arguments");
            long inputsEnd = deadline(10000);
            byte[][] files = new byte[4][];
            Inputs inputs;
            try {
                for (int i = 0; i < 4; i++) { stage=INPUT_STAGES[i]; files[i] = fetch(i, inputsEnd); }
                stage="input_validate";
                inputs = prepare(files);
            } finally {
                for (byte[] file : files) if (file != null) Arrays.fill(file, (byte)0);
            }
            remaining(inputsEnd);
            stage="trust";
            SSLContext context = tlsContext(inputs);
            remaining(inputsEnd);
            protocol.println("{\"state\":\"INPUTS_READY\",\"nonce\":\"" + args[1] + "\"}");
            protocol.flush();
            stage="go_wait"; awaitGo(System.in, args[1]);
            long networkEnd = deadline(25000);
            watchdog(25000);
            stage="https"; byte[] response=post(inputs, context, networkEnd);
            stage="projection"; List<Object> ecus = projectResponse(response);
            privacyCheck(ecus, inputs);
            remaining(networkEnd);
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("state", "RESULT"); out.put("profile", null);
            out.put("evidence", "server_candidate_only");
            out.put("eligibleForProductAllowlist", false); out.put("ecus", ecus);
            protocol.println(Json.encode(out)); protocol.flush();
            finished = true;
        } catch (Throwable e) {
            // Never emit exception messages, stack traces, remote strings or private values.
            String code = e instanceof Failure ? ((Failure)e).code : "operation_failed";
            if(e instanceof CertificateExpiredException)code="identity_expired";
            else if(e instanceof CertificateNotYetValidException)code="identity_not_yet_valid";
            else if(e instanceof CertificateException)code="certificate_invalid";
            else if(e instanceof InvalidKeySpecException)code="key_encoding";
            else if(e instanceof SSLHandshakeException)code="tls_handshake";
            else if(e instanceof SocketTimeoutException)code="io_timeout";
            protocol.println("{\"state\":\"ERROR\",\"stage\":\"" + stage + "\",\"code\":\"" + code + "\"}");
            protocol.flush(); finished = true;
            System.exit(1);
        } finally { closeActive(); }
    }

    static void androidShellGuard() throws Failure {
        try {
            Object uid=Class.forName("android.os.Process").getMethod("myUid").invoke(null);
            require(Integer.valueOf(2000).equals(uid), "runtime_guard");
        } catch (Throwable e) { throw new Failure("runtime_guard"); }
    }

    static final class Inputs {
        final X509Certificate certificate;
        final PrivateKey privateKey;
        final String token, uuid, version;
        Inputs(X509Certificate cert, PrivateKey key, String token, String uuid, String version) {
            this.certificate=cert; this.privateKey=key; this.token=token; this.uuid=uuid; this.version=version;
        }
    }
    static Inputs prepare(byte[][] files) throws Exception {
        X509Certificate cert = certificate(files[0]);
        byte[] encodedKey = der(files[1], "RSA PRIVATE KEY", "PRIVATE KEY");
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
        require(Arrays.equals(encodedKey, key.getEncoded()), "identity_encoding");
        Arrays.fill(encodedKey, (byte)0);
        cert.checkValidity();
        require(cert.getPublicKey() instanceof RSAPublicKey && key instanceof RSAPrivateKey,
            "identity_algorithm");
        boolean[] usage = cert.getKeyUsage();
        require(usage == null || (usage.length > 0 && usage[0]), "identity_usage");
        List<String> eku = cert.getExtendedKeyUsage();
        require(eku == null || eku.contains("1.3.6.1.5.5.7.3.2") || eku.contains("2.5.29.37.0"),
            "identity_usage");
        verifyPair(key, cert.getPublicKey());
        Map<String,String> prefs = preferences(files[2]);
        // Stock getCacheUpgradeUuid uses an empty string when no OTA UUID is
        // cached. Do not generate one or write a preference to fill this gap.
        String token = prefs.get("user_token"), uuid = prefs.getOrDefault("upgradeUuid", "");
        require(token != null && !"null".equalsIgnoreCase(token) && token.matches("[!-~]{1,4096}"), "token_invalid");
        require(uuid.isEmpty() || (uuid.matches("[ -~]{1,256}") && !uuid.trim().isEmpty()), "uuid_invalid");
        String version = utf8(files[3]).trim();
        require(version.matches("V[A-Za-z0-9_.-]{1,63}"), "version_invalid");
        return new Inputs(cert, key, token, uuid, version);
    }
    static void verifyPair(PrivateKey key, PublicKey publicKey) throws Exception {
        require(key instanceof RSAPrivateKey && publicKey instanceof RSAPublicKey, "identity_algorithm");
        RSAPrivateKey priv = (RSAPrivateKey)key; RSAPublicKey pub = (RSAPublicKey)publicKey;
        require(pub.getModulus().bitLength() >= 2048 && pub.getModulus().equals(priv.getModulus()), "identity_pair");
        byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
        Signature s = Signature.getInstance("SHA256withRSA"); s.initSign(key); s.update(challenge);
        byte[] signature = s.sign(); s.initVerify(publicKey); s.update(challenge);
        require(s.verify(signature), "identity_pair");
        Arrays.fill(challenge, (byte)0); Arrays.fill(signature, (byte)0);
    }
    static byte[] der(byte[] bytes, String... labels) throws Exception {
        String text = utf8(bytes).trim();
        if (text.startsWith("-----BEGIN ")) {
            boolean matched = false;
            for (String label : labels) {
                String begin = "-----BEGIN " + label + "-----", end = "-----END " + label + "-----";
                if (text.startsWith(begin) && text.endsWith(end)) {
                    text = text.substring(begin.length(), text.length()-end.length()); matched=true; break;
                }
            }
            require(matched, "identity_encoding");
        }
        String b64 = text.replaceAll("[\\r\\n\\t ]", "");
        require(!b64.isEmpty() && b64.length()%4 == 0 &&
            b64.matches("[A-Za-z0-9+/]+={0,2}"), "identity_encoding");
        byte[] out;
        try { out = Base64.getDecoder().decode(b64); } catch (IllegalArgumentException e) { throw new Failure("identity_encoding"); }
        require(Base64.getEncoder().encodeToString(out).equals(b64), "identity_encoding");
        // Reject PKCS#1/trailing data at the PKCS8/X509 parser, never repair or wrap.
        return out;
    }
    static X509Certificate certificate(byte[] bytes) throws Exception {
        byte[] encoded = der(bytes, "CERTIFICATE");
        ByteArrayInputStream in = new ByteArrayInputStream(encoded);
        X509Certificate cert = (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);
        require(in.available() == 0 && Arrays.equals(encoded, cert.getEncoded()), "identity_encoding");
        return cert;
    }
    static SSLContext tlsContext(Inputs inputs) throws Exception {
        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType()); keys.load(null);
        char[] password = new char[0];
        keys.setKeyEntry("client", inputs.privateKey, password, new java.security.cert.Certificate[]{inputs.certificate});
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, password);
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
        for (int i=0; i<TRUST_DER.length; i++) {
            X509Certificate ca = certificate(TRUST_DER[i].getBytes(StandardCharsets.US_ASCII));
            ca.checkValidity(); require(ca.getBasicConstraints() >= 0, "trust_invalid");
            trust.setCertificateEntry("ca"+i, ca);
        }
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trust);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        return context;
    }

    static Map<String,String> preferences(byte[] bytes) throws Exception {
        String xml = utf8(bytes);
        // XML has case-sensitive declaration syntax. No DTD/entity/comment/CDATA
        // declarations are needed for Android SharedPreferences. Reject before SAX.
        require(!xml.contains("<!"), "prefs_declaration");
        Map<String,String> found = new LinkedHashMap<>();
        SAXParserFactory factory = SAXParserFactory.newInstance(); factory.setNamespaceAware(false);
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) { }
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) { }
        XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("blocked"); });
        DefaultHandler handler = new DefaultHandler() {
            int depth; String wanted; StringBuilder value;
            final Set<String> seen = new HashSet<>();
            public void startElement(String uri, String local, String name, Attributes attrs) throws SAXException {
                depth++;
                if (depth > 3 || (depth == 1 && !"map".equals(name))) throw new SAXException("shape");
                if (depth == 2) {
                    String key = attrs.getValue("name");
                    if ("user_token".equals(key) || "upgradeUuid".equals(key)) {
                        if (!"string".equals(name) || !seen.add(key)) throw new SAXException("key");
                        wanted=key; value=new StringBuilder();
                    }
                } else if (depth > 2 && wanted != null) throw new SAXException("nested");
            }
            public void characters(char[] ch, int start, int length) throws SAXException {
                if (wanted != null) {
                    if (value.length()+length > 4096) throw new SAXException("length");
                    value.append(ch, start, length);
                }
            }
            public void endElement(String uri, String local, String name) {
                if (depth == 2 && wanted != null) { found.put(wanted, value.toString()); wanted=null; value=null; }
                depth--;
            }
            public void error(SAXParseException e) throws SAXException { throw new SAXException("invalid"); }
            public void fatalError(SAXParseException e) throws SAXException { throw new SAXException("invalid"); }
            public void processingInstruction(String target, String data) throws SAXException { throw new SAXException("pi"); }
        };
        reader.setContentHandler(handler); reader.setErrorHandler(handler);
        try { reader.parse(new InputSource(new StringReader(xml))); } catch (SAXException e) { throw new Failure("prefs_invalid"); }
        require(found.containsKey("user_token"), "token_missing"); return found;
    }

    static byte[] transferRequest(int index) throws Exception {
        require(index >= 0 && index < PATHS.length, "input_index");
        // Same narrowly authorized stock path mechanism as prior cache export.
        String path = "/data/local/tmp/logtool/../../../../" + PATHS[index].substring(1);
        byte[] json = ("{\"filePath\":" + Json.quote(path) + "}").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(1); out.writeInt(json.length); out.write(json); return bytes.toByteArray();
    }
    static byte[] fetch(int index, long end) throws Exception {
        Socket socket = new Socket(); active=socket;
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), EXPORT_PORT), remaining(end));
            socket.setSoTimeout(remaining(end));
            socket.getOutputStream().write(transferRequest(index)); socket.getOutputStream().flush();
            return decodeTransfer(socket.getInputStream(), index, end);
        } finally { socket.close(); active=null; }
    }
    static byte[] decodeTransfer(InputStream stream, int index, long end) throws Exception {
        DataInputStream in = new DataInputStream(stream);
        byte[] envelope = readExact(in, 12, end); ByteBuffer header = ByteBuffer.wrap(envelope);
        int magic=header.getInt(), status=header.getInt(), length=header.getInt();
        require(magic==MAGIC && status>=0 && status<=5 && length>0 && length<=MAX_JSON, "transfer_header");
        if(status==1)throw new Failure("missing_input"+index);
        if(status==5)throw new Failure("input_access_denied");
        require(status==0,"transfer_header");
        Map<String,Object> meta = object(Json.parse(utf8(readExact(in,length,end))));
        require(meta.keySet().equals(new HashSet<>(Arrays.asList("code","fileName","fileSize","md5"))), "transfer_metadata");
        require(Long.valueOf(0).equals(meta.get("code")), "transfer_metadata");
        require(PATHS[index].substring(PATHS[index].lastIndexOf('/')+1).equals(meta.get("fileName")), "transfer_name");
        Object sizeObject=meta.get("fileSize");
        require(sizeObject instanceof Long, "transfer_size"); long size=(Long)sizeObject;
        require(size>0 && size<=LIMITS[index], "transfer_size");
        Object md5=meta.get("md5"); require(md5 instanceof String && ((String)md5).matches("[0-9a-fA-F]{32}"), "transfer_md5");
        byte[] bytes=readExact(in,(int)size,end); require(readOne(in,end)==-1,"transfer_trailing");
        require(hex(MessageDigest.getInstance("MD5").digest(bytes)).equalsIgnoreCase((String)md5), "transfer_md5");
        return bytes;
    }
    static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(); for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString(); }
    static int readOne(InputStream in,long end) throws Exception {
        int timeout=remaining(end); Socket socket=active; if(socket!=null)socket.setSoTimeout(timeout);
        int b=in.read(); remaining(end); return b;
    }
    static byte[] readExact(InputStream in,int n,long end) throws Exception {
        byte[] bytes=new byte[n]; int pos=0;
        while(pos<n) {
            int timeout=remaining(end); Socket socket=active; if(socket!=null)socket.setSoTimeout(timeout);
            int count=in.read(bytes,pos,n-pos); remaining(end); require(count>0,"truncated"); pos+=count;
        }
        return bytes;
    }
    static void awaitGo(InputStream in,String nonce) throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"probe-go");t.setDaemon(true);return t; });
        Future<String> future=executor.submit(() -> {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(); int c;
            while((c=in.read())!=-1) { if(c=='\n')return bytes.toString("US-ASCII"); if(c<32 || c>126 || bytes.size()>=35)throw new Failure("go_invalid");bytes.write(c); }
            throw new Failure("go_eof");
        });
        try { require(("GO "+nonce).equals(future.get(10,TimeUnit.SECONDS)),"go_invalid"); }
        catch(TimeoutException e) { throw new Failure("go_timeout"); }
        catch(ExecutionException e) { throw new Failure(e.getCause() instanceof Failure ? ((Failure)e.getCause()).code : "go_invalid"); }
        finally { future.cancel(true); executor.shutdownNow(); }
    }

    static byte[] post(Inputs inputs,SSLContext context,long end) throws Exception {
        Socket raw=new Socket(); active=raw;
        try {
            // Exactly one address resolution and one connect attempt. No alternate IP retry.
            InetAddress address=InetAddress.getByName(HOST); remaining(end);
            raw.connect(new InetSocketAddress(address,PORT),remaining(end)); raw.setSoTimeout(remaining(end));
            SSLSocket tls=(SSLSocket)context.getSocketFactory().createSocket(raw,HOST,PORT,true); active=tls;
            SSLParameters params=tls.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS");
            params.setServerNames(Collections.singletonList(new SNIHostName(HOST))); tls.setSSLParameters(params);
            List<String> protocols=new ArrayList<>(); for(String protocol:tls.getSupportedProtocols())if("TLSv1.2".equals(protocol)||"TLSv1.3".equals(protocol))protocols.add(protocol);
            require(!protocols.isEmpty(),"tls_protocol"); tls.setEnabledProtocols(protocols.toArray(new String[0]));
            tls.setSoTimeout(remaining(end)); tls.startHandshake(); remaining(end);
            // The validated client identity is available to TLS. A server may
            // authenticate the token without requesting a client certificate.
            // Do not create a second connection or a credential fallback.
            byte[] request=httpRequest(inputs); tls.getOutputStream().write(request); tls.getOutputStream().flush();
            Arrays.fill(request,(byte)0); remaining(end);
            return readHttp(tls.getInputStream(),end);
        } finally { closeActive(); active=null; raw.close(); }
    }
    static byte[] httpRequest(Inputs inputs) throws Exception {
        String body="{\"version\":"+Json.quote(inputs.version)+",\"uuid\":"+Json.quote(inputs.uuid)+"}";
        byte[] payload=body.getBytes(StandardCharsets.UTF_8);
        String headers="POST "+URI+" HTTP/1.1\r\nHost: "+HOST+":"+PORT+"\r\nUser-Agent: BYD\r\n"+
            "Content-Type: application/json;charset=utf-8\r\nAccept: application/json\r\nAccept-Encoding: identity\r\n"+
            "Connection: close\r\nAuthorization: basic"+inputs.token+"\r\nContent-Length: "+payload.length+"\r\n\r\n";
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(payload);return out.toByteArray();
    }
    static String httpLine(InputStream in,long end,int max) throws Exception {
        return httpLine(in,end,max,false);
    }
    static String httpLine(InputStream in,long end,int max,boolean header) throws Exception {
        StringBuilder s=new StringBuilder();
        while(true) { int b=readOne(in,end);require(b>=0,"http_truncated");
            if(b=='\r') { require(readOne(in,end)=='\n',"http_line");return s.toString(); }
            require(((b>=32 && b<=126)||(header && b=='\t')) && s.length()<max,"http_line");s.append((char)b);
        }
    }
    static byte[] readHttp(InputStream in,long end) throws Exception {
        String status=httpLine(in,end,256);
        if(status.matches("HTTP/1\\.[01] (?:401|403)(?: [ -~]*)?"))throw new Failure("http_auth");
        require(status.matches("HTTP/1\\.[01] 200(?: [ -~]*)?"),"http_status");
        Map<String,String> headers=new LinkedHashMap<>();int total=0;
        List<String> used=Arrays.asList("content-type","content-encoding","content-length","transfer-encoding");
        while(true) {
            String line=httpLine(in,end,4096,true);total+=line.length()+2;require(total<=16384,"http_headers_limit");if(line.isEmpty())break;
            int colon=line.indexOf(':');require(colon>0,"http_header_syntax");String name=line.substring(0,colon).toLowerCase(Locale.ROOT);
            require(name.matches("[!#$%&'*+.^_`|~0-9a-z-]+"),"http_header_syntax");
            // Repeated fields such as Set-Cookie are legal (RFC 9110 section 5.3).
            // This one-shot client neither uses nor saves cookies or other unused
            // fields. Keep the fields controlling body parsing unambiguous.
            if(used.contains(name)) {
                require(!headers.containsKey(name),"http_header_duplicate");
                headers.put(name,line.substring(colon+1).trim());
            }
        }
        String type=headers.get("content-type"); require(type!=null && type.matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?"),"http_content_type");
        String encoding=headers.get("content-encoding");require(encoding==null||"identity".equalsIgnoreCase(encoding),"http_encoding");
        String length=headers.get("content-length"), transfer=headers.get("transfer-encoding");
        require(length==null||transfer==null,"http_framing");byte[] bytes;
        if(length!=null) {
            require(length.matches("0|[1-9][0-9]{0,7}"),"http_length");int n=Integer.parseInt(length);require(n>0&&n<=MAX_BODY,"http_length");bytes=readExact(in,n,end);
        } else if(transfer!=null) {
            require("chunked".equalsIgnoreCase(transfer),"http_framing");ByteArrayOutputStream out=new ByteArrayOutputStream();
            while(true) { String chunk=httpLine(in,end,32);require(chunk.matches("[0-9a-fA-F]{1,8}"),"http_chunk");long size=Long.parseLong(chunk,16);
                require(size<=MAX_BODY-out.size(),"http_length");if(size==0){require(httpLine(in,end,0).isEmpty(),"http_trailer");break;}
                out.write(readExact(in,(int)size,end));require(readOne(in,end)=='\r'&&readOne(in,end)=='\n',"http_chunk"); }
            bytes=out.toByteArray();
        } else {
            ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
            while((b=readOne(in,end))!=-1){require(out.size()<MAX_BODY,"http_length");out.write(b);}return out.toByteArray();
        }
        require(readOne(in,end)==-1,"http_trailing");return bytes;
    }
    static List<Object> projectResponse(byte[] bytes) throws Exception {
        require(bytes.length>0 && bytes.length<=MAX_BODY,"response_length");Map<String,Object> root=object(Json.parse(utf8(bytes)));
        Object status=root.get("status");
        if(Arrays.asList("101","117","BYD101","BYD102","BYD103","BYD104").contains(status))throw new Failure("application_auth");
        if("114".equals(status))throw new Failure("application_registration");
        require("100".equals(status),"application_status");Object rows=root.get("ecuList");
        require(rows instanceof List && ((List<?>)rows).size()>0 && ((List<?>)rows).size()<=2048,"ecu_list");
        List<Object> result=new ArrayList<>();Set<String> identities=new HashSet<>();
        try { for(Object value:(List<?>)rows) {
            Map<String,Object> row=object(value), projected=new LinkedHashMap<>();Object id=row.get("ecuId");
            // Stock ECUVersion.json includes "rear CPD radar" and "FULIN PM".
            // Keep those ASCII spaces verbatim: they are part of the ECU key.
            require(id instanceof String && ((String)id).matches("[A-Za-z0-9_ -]{1,128}") &&
                !((String)id).trim().isEmpty() && identities.add((String)id),"ecu_identity");
            for(String field:FIELDS) if(row.containsKey(field)) {Object v=row.get(field);require(v==null || (v instanceof String && ((String)v).length()<=128),"ecu_field");projected.put(field,v);}
            Object pair=row.get("ecuDiaId"), transport=row.get("ecuReprogramType");
            Integer left=null,right=null;
            if(pair!=null && !"".equals(pair)) {
                require(pair instanceof String && ((String)pair).matches("[0-9a-fA-F]{1,4}/[0-9a-fA-F]{1,4}"),"ecu_route");
                String[] parts=((String)pair).split("/");left=Integer.parseInt(parts[0],16);right=Integer.parseInt(parts[1],16);
            }
            Map<String,Object> route=new LinkedHashMap<>();
            if("can".equals(transport)||"canfd".equals(transport)) {require(left!=null,"ecu_route");route.put("kind","can");route.put("requestId",left);route.put("responseId",right);route.put("physicalBus",null);}
            else if("ethernet".equals(transport)||"doip".equals(transport)) {
                Object logical=row.get("logicAddress");require(logical==null || (logical instanceof String && (((String)logical).isEmpty()||((String)logical).matches("[0-9a-fA-F]{1,4}"))),"ecu_logical");
                // Stock DoipDiagnostic accepts a logical address without a
                // pair. Preserve the absent pair rather than inventing one.
                require(left!=null || (logical instanceof String && !((String)logical).isEmpty()),"ecu_route");
                route.put("kind","ethernet");route.put("pairTarget",left);route.put("pairClient",right);route.put("target",logical==null||"".equals(logical)?left:Integer.parseInt((String)logical,16));
            } else throw new Failure("ecu_transport");
            projected.put("route",route);projected.put("evidence","server_candidate_only");projected.put("eligibleForProductAllowlist",false);result.add(projected);
        } } catch (Failure failure) { throw new Failure("invalid_ecu_projection"); }
        return result;
    }
    static void privacyCheck(Object value, Inputs inputs) throws Failure {
        if(value instanceof String) {
            String text=(String)value;
            require(!containsPrivate(text, inputs.token) && !containsPrivate(text, inputs.uuid) &&
                !text.equals(inputs.version), "privacy_projection");
        } else if(value instanceof Map) {
            for(Object child:((Map<?,?>)value).values())privacyCheck(child,inputs);
        } else if(value instanceof List) {
            for(Object child:(List<?>)value)privacyCheck(child,inputs);
        }
    }
    static boolean containsPrivate(String text, String value) {
        return !value.isEmpty() && (value.length() >= 8 ? text.contains(value) : text.equals(value));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object o) throws Failure {require(o instanceof Map,"json_object");return (Map<String,Object>)o;}
    static String utf8(byte[] bytes) throws Exception {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException e){throw new Failure("utf8");}
    }

    /** Small strict JSON parser: duplicate keys, trailing data, malformed UTF-16,
     * excessive depth/count, NaN and non-JSON numbers are rejected globally. */
    static final class Json {
        final String s; int pos,nodes; Json(String s){this.s=s;}
        static Object parse(String s)throws Failure {Json p=new Json(s);Object v=p.value(0);p.space();require(p.pos==s.length(),"json_trailing");return v;}
        void space(){while(pos<s.length()&&" \t\r\n".indexOf(s.charAt(pos))>=0)pos++;}
        char take()throws Failure {require(pos<s.length(),"json_truncated");return s.charAt(pos++);}
        Object value(int depth)throws Failure {
            require(depth<=32 && ++nodes<=100000,"json_limit");space();char c=take();
            if(c=='"')return string();
            if(c=='{') {Map<String,Object> m=new LinkedHashMap<>();space();if(pos<s.length()&&s.charAt(pos)=='}'){pos++;return m;}
                while(true){space();require(take()=='"',"json_key");String key=string();require(!m.containsKey(key),"json_duplicate");space();require(take()==':',"json_colon");m.put(key,value(depth+1));space();c=take();if(c=='}')return m;require(c==',',"json_separator");}}
            if(c=='[') {List<Object> a=new ArrayList<>();space();if(pos<s.length()&&s.charAt(pos)==']'){pos++;return a;}
                while(true){a.add(value(depth+1));space();c=take();if(c==']')return a;require(c==',',"json_separator");}}
            if(c=='t'){literal("rue");return true;}if(c=='f'){literal("alse");return false;}if(c=='n'){literal("ull");return null;}
            int start=pos-1;while(pos<s.length()&&"0123456789.eE+-".indexOf(s.charAt(pos))>=0)pos++;String num=s.substring(start,pos);
            require(num.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")&&num.length()<=128,"json_number");
            try {return Long.valueOf(num);}catch(NumberFormatException e){return new java.math.BigDecimal(num);}
        }
        void literal(String rest)throws Failure {require(s.startsWith(rest,pos),"json_literal");pos+=rest.length();}
        String string()throws Failure {
            StringBuilder b=new StringBuilder();while(true){char c=take();if(c=='"')break;require(c>=32,"json_string");
                if(c=='\\'){char e=take();if(e=='u'){require(pos+4<=s.length(),"json_escape");String h=s.substring(pos,pos+4);require(h.matches("[0-9a-fA-F]{4}"),"json_escape");c=(char)Integer.parseInt(h,16);pos+=4;}
                    else {int index="\"\\/bfnrt".indexOf(e);require(index>=0,"json_escape");c="\"\\/\b\f\n\r\t".charAt(index);}}
                b.append(c);require(b.length()<=MAX_BODY,"json_limit");}
            for(int i=0;i<b.length();i++){char c=b.charAt(i);if(Character.isHighSurrogate(c)){require(i+1<b.length()&&Character.isLowSurrogate(b.charAt(++i)),"json_surrogate");}else require(!Character.isLowSurrogate(c),"json_surrogate");}return b.toString();
        }
        static String quote(String s){StringBuilder b=new StringBuilder("\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
        static String encode(Object v){if(v==null)return "null";if(v instanceof String)return quote((String)v);if(v instanceof Map){List<String>a=new ArrayList<>();for(Map.Entry<?,?>e:((Map<?,?>)v).entrySet())a.add(quote((String)e.getKey())+":"+encode(e.getValue()));return "{"+String.join(",",a)+"}";}if(v instanceof List){List<String>a=new ArrayList<>();for(Object e:(List<?>)v)a.add(encode(e));return "["+String.join(",",a)+"]";}return v.toString();}
    }
}
