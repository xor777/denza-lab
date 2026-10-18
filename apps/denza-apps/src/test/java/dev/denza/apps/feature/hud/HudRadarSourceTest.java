package dev.denza.apps.feature.hud;

import dev.denza.hudlink.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** HUD Speed in the map window: its warning bottom left, its GPS bars bottom right, never the navigator's fields. */
public class HudRadarSourceTest {
    private final HudScene scene = new HudScene();
    private long now;
    private final HudSink sink = new HudSink() {
        public void layer(HudSource s,String id,HudRole r,HudRect rect,HudImage image,float a,long ttl) { scene.layer(s,id,r,rect,image,a,ttl,now); }
        public void removeLayer(HudSource s,String id) { scene.removeLayer(s,id); }
        public void fields(HudSource s,HudRole r,HudFields f,long ttl) { scene.fields(s,r,f,ttl,now); }
        public void withdraw(HudSource s,HudRole r) { scene.withdraw(s,r); }
        public void withdraw(HudSource s) { scene.withdraw(s); }
    };
    private static HudImage png(int width, int height) {
        byte[] png = {(byte)0x89,'P','N','G','\r','\n',0x1a,'\n',0,0,0,13,'I','H','D','R',
                0,0,(byte)(width>>8),(byte)width,0,0,(byte)(height>>8),(byte)height,8,6,0,0,0};
        return HudImage.png(png);
    }
    private final HudRadarSource radar = new HudRadarSource(sink, new HudRadarSource.Renderer() {
        public HudImage warning(HudRadarWarning w) { return png(110, HudRadarPainter.TILE); }
        public HudImage gps(int level) { return level > 0 ? png(HudRadarPainter.GPS, HudRadarPainter.GPS) : null; }
    });
    private HudRadarWarning warning(long at) {
        return new HudRadarWarning(at,true,HudRadarWarning.GPS_FIX,true,1,1,"Камера",200,60,1,false,-1,"cam_type_1","cam_speed_box_red");
    }
    private void route() {
        scene.fields(HudSource.MODULE,HudRole.MANEUVER,new HudFields.Builder().number(9,450).text(10,"Улица").build(),10000,now);
        scene.fields(HudSource.MODULE,HudRole.ROAD_LIMIT,new HudFields.Builder().number(11,90).build(),10000,now);
    }
    private HudLayer layer(String id) {
        for (HudLayer l : scene.compose(now).layers(HudWindow.MAP)) if (l.id.equals(id)) return l;
        return null;
    }

    @Test public void theWarningSitsBottomLeftOfTheCursorAndTheBarsBottomRight() {
        now=1000; route();
        radar.publish(warning(now),now);
        assertEquals(new HudRect(6,140,110,34),layer(HudRadarSource.WARNING).rect);
        assertEquals(new HudRect(272,152,22,22),layer(HudRadarSource.GPS).rect);
        // Clear of the cursor (hud-scene.js EVENTS.cursor [134,120,32,44]) and of the window's top.
        assertTrue(HudRadarSource.warningRect(500).x+HudRadarSource.warningRect(500).width<=134);
    }
    @Test public void delayedWarningExpiresAtTheOriginalDeadlineAndDoesNotReplaceNavigation() {
        now=2000; route();
        radar.publish(warning(1000),now);
        HudComposition composed=scene.compose(2000);
        assertEquals(2,composed.layers(HudWindow.MAP).size());
        assertEquals(HudSource.MODULE,composed.winner(HudRole.MANEUVER));
        assertEquals(HudSource.MODULE,composed.winner(HudRole.ROAD_LIMIT));
        assertEquals(0,scene.compose(4000).layers(HudWindow.MAP).size());
        assertEquals(HudMode.NAVIGATION,scene.compose(4000).mode);
    }
    @Test public void aRunningRadarWithNothingToSayKeepsOnlyItsBars() {
        now=1000; route();
        radar.publish(warning(now),now);
        radar.publish(HudRadarWarning.idle(1100,HudRadarWarning.GPS_SEARCHING),now=1100);
        assertNull(layer(HudRadarSource.WARNING));
        assertNotNull(layer(HudRadarSource.GPS));
    }
    @Test public void aStoppedRadarShowsNothing() {
        now=1000; route();
        radar.publish(warning(now),now);
        radar.publish(HudRadarWarning.clear(1100),now=1100);
        assertTrue(scene.compose(1100).layers(HudWindow.MAP).isEmpty());
        assertEquals(HudMode.NAVIGATION,scene.compose(1100).mode);
    }
    @Test public void radarDoesNotOpenAnUnverifiedStandaloneMode() {
        now=1000; radar.publish(warning(now),now);
        assertEquals(HudMode.OFF,scene.compose(now).mode);
        assertTrue(scene.compose(now).layers(HudWindow.MAP).isEmpty());
    }
}
