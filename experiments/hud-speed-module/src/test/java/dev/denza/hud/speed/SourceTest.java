package dev.denza.hud.speed;

import dev.denza.hudlink.HudRadarWarning;
import java.util.*;

/** Engine-shaped fixtures; checks the meaning of the obfuscated fields, not a second detector. */
public final class SourceTest {
    public static final class Service { public Radar c = new Radar(); public Settings d = new Settings(); }
    public static final class Settings { public byte S() { return 3; } }
    public static final class Position { public float d = 55, e = 37; public float a() { return 10; } }
    public static final class Radar {
        public Boolean w = true;
        public boolean gps = true, G;
        public Camera H;
        public int J;
        public Position n = new Position();
        public List<Camera> objects = new ArrayList<>();
        public List<Camera> f() { return objects; }
        public boolean h() { return gps; }
    }
    public static final class Camera {
        final int id, type, unit, limit;
        Camera(int id, int type, int unit, int limit) { this.id=id; this.type=type; this.unit=unit; this.limit=limit; }
        public int j() { return id; }
        public short v() { return (short) type; }
        public int u() { return unit; }
        public float k() { return 55.001f; }
        public float l() { return 37.001f; }
        public short s(byte vehicle) { check(vehicle==3, "vehicle category confused with units"); return (short) limit; }
    }
    private static final HudSpeedSource.Labels LABELS = new HudSpeedSource.Labels() {
        public String title(int type) { return type == 1 ? "Камера" : "Опасность " + type; }
        public int distance(double a, double b, double c, double d) {
            check(a > c && b > d && c==55 && d==37, "distance inputs"); return 150;
        }
    };
    public static void main(String[] args) throws Exception {
        Service service = new Service();
        check(!read(service).active, "empty list");
        service.c.objects.add(new Camera(70, 1, 1, 60));
        service.c.objects.add(new Camera(80, 5, 1, 0));
        HudRadarWarning point = read(service);
        check(point.active && point.objectId==70 && point.limit==60 && point.meters==150 && !point.section, "selected warning");
        service.c.gps=false; check(!read(service).active, "GPS loss must clear retained list");
        service.c.gps=true; service.c.w=false; check(!read(service).active, "stopped engine");
        service.c.w=true; service.c.objects.clear();
        service.c.objects.add(new Camera(99, 5, 2, 40));
        HudRadarWarning other=read(service);
        check(other.type==5 && other.title.equals("Опасность 5") && other.unit==2 && other.limit==40, "danger and mph");
        service.c.G=true; service.c.H=new Camera(42, 41, 1, 90); service.c.J=650;
        check(read(service).objectId==99, "active warning precedes ongoing section, like original overlay");
        service.c.objects.clear();
        HudRadarWarning section=read(service);
        check(section.section && section.meters==650 && section.averageSpeed==36 && section.limit==90, "ongoing average section");
        service.c.H=null; check(!read(service).active, "incomplete section state");
        System.out.println("HUD Speed source: point, priority, GPS, stop, mph, danger, section passed");
    }
    private static HudRadarWarning read(Service service) throws Exception { return HudSpeedSource.read(service, LABELS, 1_000); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
