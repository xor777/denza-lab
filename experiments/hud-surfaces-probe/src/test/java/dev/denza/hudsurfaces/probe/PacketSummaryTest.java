package dev.denza.hudsurfaces.probe;

import static org.junit.Assert.assertEquals;

import dev.denza.hudlink.HudProtocol;
import dev.denza.hudsurfaces.probe.SurfacePackets.CameraPhase;
import org.junit.Test;

/** The log line is what the owner's run is read from: it must say what went out. */
public class PacketSummaryTest {
    @Test
    public void cameraPacketWithoutNavigation() {
        assertEquals("{2=2 16=1 17=1 18=300}", PacketSummary.of(HudProtocol.TOPIC_ROAD,
                SurfacePackets.cameraFields(false, CameraPhase.CAMERA, 0, null)));
    }

    @Test
    public void navigatingPacketNamesThePictureByItsSize() {
        assertEquals("{2=2 16=2 8=png80x80/29B 9=800 10=\"ПРОБА\" 11=60 17=1 18=300"
                        + " 26=\"5,0 км\" 27=\"10 мин\" 28=11}",
                PacketSummary.of(HudProtocol.TOPIC_ROAD, SurfacePackets.cameraFields(
                        true, CameraPhase.CAMERA_LIMIT, 0, FakeArt.ARROW)));
    }

    @Test
    public void warningPacket() {
        assertEquals("{2=2 16=2 8=png80x80/29B 9=250 10=\"60\" 28=11}",
                PacketSummary.of(HudProtocol.TOPIC_ROAD,
                        SurfacePackets.warningManeuver(FakeArt.CAMERA, 250, "60")));
    }

    @Test
    public void clearPacket() {
        assertEquals("{2=2 16=1}", PacketSummary.of(HudProtocol.TOPIC_ROAD, SurfacePackets.clear()));
    }

    @Test
    public void mapFrameIsThePictureInsideTheBase64() {
        assertEquals("{1=base64:png300x180/29B}",
                PacketSummary.of(HudProtocol.TOPIC_MAP, SurfacePackets.map(FakeArt.BLACK)));
    }

    @Test
    public void malformedBytesAreSaidSoInsteadOfThrowing() {
        assertEquals("{malformed 2 B}",
                PacketSummary.of(HudProtocol.TOPIC_ROAD, new byte[] {0x0a, 0x7f}));
    }

    @Test
    public void topicNames() {
        assertEquals("road", PacketSummary.topicName(HudProtocol.TOPIC_ROAD));
        assertEquals("map", PacketSummary.topicName(HudProtocol.TOPIC_MAP));
    }
}
