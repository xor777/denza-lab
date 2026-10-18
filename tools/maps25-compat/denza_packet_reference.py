#!/usr/bin/env python3
"""Build synthetic golden packets using methods extracted verbatim from Denza Apps.
No Android device, personal trace or network is used. Java 17 is required.
"""
from pathlib import Path
import hashlib
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'apps/denza-apps/src/main/java/dev/denza/apps/feature/hud/HudSomeIpClient.java'

def method(source, signature):
    start = source.index(signature)
    body = source.index('{', start)
    depth = 1
    end = body + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def reference_source():
    source = SOURCE.read_text()
    signatures = ['private static byte[] buildPayload(\n', 'static String routeDistance(',
                  'private static String formatDistance(', 'private static byte[] embed(',
                  'private static void stringField(', 'private static void bytesField(',
                  'private static void intField(', 'private static void doubleField(',
                  'private static void varint(']
    methods = '\n'.join(method(source, signature) for signature in signatures)
    stubs = '''
    static class HudGuidance {
        Integer getRemainingDistanceMeters() { return 5000; }
        Integer getRemainingTimeSeconds() { return 600; }
        int getManeuverDistanceMeters() { return 84; }
        String getNextRoadName() { return "Test road"; }
        Integer getSpeedLimitKmh() { return null; }
        String getRemainingTimeText() { return "10 мин"; }
        HudManeuver getManeuver() { return new HudManeuver(); }
    }
    static class HudManeuver { int getStockId() { return 2; } }
    static class HudArGeometry {
        double getVehicleLongitude() { return 10.0; }
        double getVehicleLatitude() { return 20.0; }
        double getVehicleSpeedMetersPerSecond() { return 8.0; }
        double getVehicleAltitudeMeters() { return 100.0; }
        String getGuideLine() { return "[[10,20,0],[10,20.001,0],[10.001,20.001,0]]"; }
        String getGuidePoint() { return "10,20.001,0"; }
        double getVehicleHeadingDegrees() { return 0.0; }
        double getNavigatingRatio() { return 0.0; }
    }
    public static void main(String[] args) {
        HudGuidance guidance = new HudGuidance();
        byte[] icon = new byte[] {1, 2, 3};
        System.out.println("compact=" + java.util.Base64.getEncoder().encodeToString(buildPayload(false, guidance, icon, null)));
        System.out.println("ar=" + java.util.Base64.getEncoder().encodeToString(buildPayload(false, guidance, icon, new HudArGeometry())));
        System.out.println("clear=" + java.util.Base64.getEncoder().encodeToString(buildPayload(true, null, null, null)));
    }
    '''
    return ('import java.io.ByteArrayOutputStream;\nimport java.nio.charset.StandardCharsets;\n'
            'import java.util.Locale;\npublic class Reference {\nstatic final int STOCK_COUNTER=2;\n'
            + methods + stubs + '\n}')

if __name__ == '__main__':
    jdk = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
    with tempfile.TemporaryDirectory(prefix='denza-reference-') as tmp:
        java = Path(tmp) / 'Reference.java'
        java.write_text(reference_source())
        subprocess.run([str(jdk/'javac'), str(java)], check=True)
        print('# HudSomeIpClient.java SHA256 ' + hashlib.sha256(SOURCE.read_bytes()).hexdigest(), flush=True)
        subprocess.run([str(jdk/'java'), '-cp', tmp, 'Reference'], check=True)


def encode_recorded_packets(payloads):
    """Run recorded semantic fields/geometry through the actual Denza Apps encoder.
    Input is a list of RoadInfo payloads; absent route totals remain absent.
    """
    source = reference_source()
    start = source.index('    static class HudGuidance')
    source = source[:start] + r'''
    static java.util.Map<Integer, Object> values;
    static byte[] bytes(int field) { return (byte[]) values.get(field); }
    static String text(int field) { byte[] b=bytes(field); return b == null ? "" : new String(b, StandardCharsets.UTF_8); }
    static Integer integer(int field) { Long v=(Long) values.get(field); return v == null ? null : Math.toIntExact(v); }
    static double number(int field) { return (Double) values.get(field); }
    static class HudGuidance {
        Integer getRemainingDistanceMeters() { return integer(3); }
        Integer getRemainingTimeSeconds() { return integer(4); }
        int getManeuverDistanceMeters() { return integer(9); }
        String getNextRoadName() { return text(10); }
        Integer getSpeedLimitKmh() { return integer(11); }
        String getRemainingTimeText() { return text(27); }
        HudManeuver getManeuver() { return new HudManeuver(); }
    }
    static class HudManeuver { int getStockId() { return integer(28); } }
    static class HudArGeometry {
        double getVehicleLongitude() { return number(19); }
        double getVehicleLatitude() { return number(20); }
        double getVehicleSpeedMetersPerSecond() { return integer(21); }
        double getVehicleAltitudeMeters() { return integer(22); }
        String getGuideLine() { return text(30); }
        String getGuidePoint() { return text(31); }
        double getVehicleHeadingDegrees() { return number(32); }
        double getNavigatingRatio() { return number(33); }
    }
    static long readVarint(java.nio.ByteBuffer b) {
        long value=0;
        for (int shift=0; shift<64; shift+=7) {
            int n=b.get() & 255; value|=(long)(n&127)<<shift;
            if ((n&128)==0) return value;
        }
        throw new IllegalArgumentException("varint");
    }
    static void decode(byte[] payload) {
        java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (readVarint(b)!=10) throw new IllegalArgumentException("envelope");
        int length=Math.toIntExact(readVarint(b));
        if (length!=b.remaining()) throw new IllegalArgumentException("length");
        values=new java.util.HashMap<>();
        while (b.hasRemaining()) {
            int tag=(int)readVarint(b); Object value;
            switch (tag&7) {
                case 0: value=readVarint(b); break;
                case 1: value=b.getDouble(); break;
                case 2: byte[] data=new byte[Math.toIntExact(readVarint(b))]; b.get(data); value=data; break;
                default: throw new IllegalArgumentException("wire");
            }
            values.put(tag>>>3, value);
        }
    }
    public static void main(String[] args) throws Exception {
        java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        String line;
        while ((line=reader.readLine())!=null) {
            decode(java.util.Base64.getDecoder().decode(line));
            boolean clear=integer(16)==1;
            HudArGeometry ar=text(30).isEmpty()?null:new HudArGeometry();
            byte[] result=buildPayload(clear, new HudGuidance(), bytes(8), ar);
            System.out.println(java.util.Base64.getEncoder().encodeToString(result));
        }
    }
}'''
    jdk = Path('/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin')
    with tempfile.TemporaryDirectory(prefix='denza-replay-encoder-') as tmp:
        java = Path(tmp) / 'Reference.java'
        java.write_text(source)
        subprocess.run([str(jdk/'javac'), str(java)], check=True)
        result = subprocess.run([str(jdk/'java'), '-cp', tmp, 'Reference'],
                                input='\n'.join(payloads)+'\n', text=True,
                                capture_output=True, check=True)
    packets = result.stdout.splitlines()
    if len(packets) != len(payloads):
        raise ValueError('reference encoder lost frames')
    return packets
