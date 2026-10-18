package dev.denza.apps.feature.hud;

import dev.denza.hudlink.*;
import org.junit.Test;
import static org.junit.Assert.*;

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
    private int maneuver;
    private final HudRadarSource radar = new HudRadarSource(sink, w -> HudImage.png(java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7c8AAAAASUVORK5CYII=")), () -> maneuver);
    private HudRadarWarning warning(long at) { return new HudRadarWarning(at,true,1,1,"Камера",200,60,1,false,-1); }

    @Test public void delayedWarningExpiresAtTheOriginalDeadlineAndDoesNotReplaceNavigation() {
        now=2000;
        scene.fields(HudSource.MODULE,HudRole.MANEUVER,new HudFields.Builder().number(9,450).text(10,"Улица").build(),10000,now);
        scene.fields(HudSource.MODULE,HudRole.ROAD_LIMIT,new HudFields.Builder().number(11,90).build(),10000,now);
        radar.publish(warning(1000),now);
        HudComposition composed=scene.compose(2000);
        assertEquals(1,composed.layers(HudWindow.MAP).size());
        assertEquals(HudSource.MODULE,composed.winner(HudRole.MANEUVER));
        assertEquals(HudSource.MODULE,composed.winner(HudRole.ROAD_LIMIT));
        assertEquals(0,scene.compose(4000).layers(HudWindow.MAP).size());
        assertEquals(HudMode.NAVIGATION,scene.compose(4000).mode);
    }
    @Test public void radarDoesNotOpenAnUnverifiedStandaloneMode() {
        now=1000; radar.publish(warning(now),now);
        assertEquals(HudMode.OFF,scene.compose(now).mode);
        assertTrue(scene.compose(now).layers(HudWindow.MAP).isEmpty());
    }
    @Test public void theBadgeTakesTheTopCornerTheRouteDoesNotTurnInto() {
        assertTrue(HudRadarSource.leftCorner(2));   // right turn: the left corner is free
        assertTrue(HudRadarSource.leftCorner(11));  // straight on: the map marks go right first
        assertTrue(HudRadarSource.leftCorner(0));
        assertFalse(HudRadarSource.leftCorner(1));  // left turn
        assertFalse(HudRadarSource.leftCorner(3));
        assertFalse(HudRadarSource.leftCorner(7));
        assertEquals(new HudRect(4,4,72,58),HudRadarSource.rect(true));
        assertEquals(new HudRect(224,4,72,58),HudRadarSource.rect(false));
    }
    @Test public void oneWarningKeepsItsCornerWhileTheManoeuvreChanges() {
        now=1000;
        scene.fields(HudSource.MODULE,HudRole.MANEUVER,new HudFields.Builder().number(9,450).build(),10000,now);
        maneuver=1; radar.publish(warning(now),now);
        assertEquals(HudRadarSource.rect(false),scene.compose(now).layers(HudWindow.MAP).get(0).rect);
        maneuver=2; now=1500; radar.publish(warning(now),now);
        assertEquals(HudRadarSource.rect(false),scene.compose(now).layers(HudWindow.MAP).get(0).rect);
        // The next object chooses again.
        now=2000; radar.publish(new HudRadarWarning(now,true,2,1,"Камера",300,60,1,false,-1),now);
        assertEquals(HudRadarSource.rect(true),scene.compose(now).layers(HudWindow.MAP).get(0).rect);
    }
    @Test public void stopRemovesTheWarningImmediately() {
        now=1000;
        scene.fields(HudSource.MODULE,HudRole.MANEUVER,new HudFields.Builder().number(9,450).build(),10000,now);
        radar.publish(warning(now),now);
        radar.publish(HudRadarWarning.clear(1100),1100);
        assertTrue(scene.compose(1100).layers(HudWindow.MAP).isEmpty());
        assertEquals(HudMode.NAVIGATION,scene.compose(1100).mode);
    }
}
