package dev.denza.hudlink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;

/** The decisions of one {@link HudScene#compose}: the mode, who won what, and what each window shows. */
public final class HudComposition {
    public final HudMode mode;
    /** The earliest time something published dies; {@code Long.MAX_VALUE} when nothing will. */
    public final long nextChange;
    private final EnumMap<HudRole, HudSource> owners;
    private final EnumMap<HudRole, HudFields> values;
    private final EnumMap<HudWindow, List<HudLayer>> drawn;
    private final EnumMap<HudWindow, Long> versions;

    HudComposition(HudMode mode, EnumMap<HudRole, HudSource> owners,
            EnumMap<HudRole, HudFields> values, EnumMap<HudWindow, List<HudLayer>> drawn,
            EnumMap<HudWindow, Long> versions, long nextChange) {
        this.mode = mode;
        this.owners = owners;
        this.values = values;
        this.drawn = drawn;
        this.versions = versions;
        this.nextChange = nextChange;
    }

    /** The source shown for {@code role}; null when none is, and always without navigation. */
    public HudSource winner(HudRole role) {
        return owners.get(role);
    }

    /** {@code role}'s fields as shown; null when none are. */
    public HudFields fields(HudRole role) {
        return values.get(role);
    }

    /** The layers {@code window} shows, bottom first; empty when it shows nothing. */
    public List<HudLayer> layers(HudWindow window) {
        List<HudLayer> list = drawn.get(window);
        return list == null ? Collections.<HudLayer>emptyList() : Collections.unmodifiableList(list);
    }

    /** Raised whenever what {@code window} shows changes; equal versions draw the same. */
    public long version(HudWindow window) {
        Long version = versions.get(window);
        return version == null ? 0L : version;
    }

    /**
     * The single layer {@code window} shows when it covers the window exactly, opaque, with a
     * picture of the window's size: its PNG goes to the HUD as it is, not drawn again. Null
     * otherwise.
     */
    public HudLayer passThrough(HudWindow window) {
        List<HudLayer> list = drawn.get(window);
        return list != null && list.size() == 1 && list.get(0).coversAlone() ? list.get(0) : null;
    }

    /** The road packet for {@link HudMode#NAVIGATION}; null without navigation. */
    public byte[] road() {
        if (mode != HudMode.NAVIGATION) {
            return null;
        }
        return HudRoadPacket.navigating(new ArrayList<>(values.values()));
    }

    /**
     * The clear for what this composition shows: navigation off, and «no lanes» when lanes are
     * shown. Null without navigation, where the clear of the last navigating one stands.
     */
    public byte[] clear() {
        if (mode != HudMode.NAVIGATION) {
            return null;
        }
        return HudRoadPacket.clear(values.containsKey(HudRole.LANES));
    }
}
