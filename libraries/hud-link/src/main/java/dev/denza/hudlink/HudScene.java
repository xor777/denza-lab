package dev.denza.hudlink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;

/**
 * What every source has published, and the decisions on it (docs/hud-output-architecture.md, 2-3):
 * which layers a window shows in which order, which source wins each role's fields, and which
 * mode the HUD is in. Times come from the caller, so the decisions run on the JVM; nothing here
 * draws or sends. One thread.
 */
public final class HudScene {
    /**
     * How long a layer lives unless published again: a source that has gone quiet leaves the
     * glass on its own, as the module emptied its frame after 1.5 s.
     */
    public static final long LAYER_TTL_MS = 1_500L;

    private static final long[] NOTHING = new long[0];

    private final List<HudLayer> layers = new ArrayList<>();
    private final List<Published> fields = new ArrayList<>();
    private final EnumMap<HudWindow, long[]> shown = new EnumMap<>(HudWindow.class);
    private final EnumMap<HudWindow, Long> versions = new EnumMap<>(HudWindow.class);
    private long serials;
    private long orders;

    /** Shows or replaces layer {@code id} of {@code source}; it dies at {@code now + ttlMs}. */
    public void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
            float opacity, long ttlMs, long now) {
        check(source, role, true);
        if (id == null || rect == null || image == null) {
            throw new IllegalArgumentException("a layer needs an id, a rectangle and a picture");
        }
        if (!(opacity >= 0f && opacity <= 1f)) {
            throw new IllegalArgumentException("opacity " + opacity);
        }
        checkLifetime(ttlMs);
        for (int index = 0; index < layers.size(); index++) {
            HudLayer old = layers.get(index);
            if (old.source == source && old.id.equals(id)) {
                long serial = old.looksLike(role, rect, image, opacity) ? old.serial : ++serials;
                layers.set(index, new HudLayer(source, id, role, rect, image, opacity,
                        now, now + ttlMs, serial, old.order));
                return;
            }
        }
        layers.add(new HudLayer(source, id, role, rect, image, opacity,
                now, now + ttlMs, ++serials, ++orders));
    }

    /** Takes layer {@code id} of {@code source} away. */
    public void removeLayer(HudSource source, String id) {
        for (Iterator<HudLayer> each = layers.iterator(); each.hasNext(); ) {
            HudLayer layer = each.next();
            if (layer.source == source && layer.id.equals(id)) {
                each.remove();
            }
        }
    }

    /** Sets {@code role}'s fields from {@code source}; they die at {@code now + ttlMs}. */
    public void fields(HudSource source, HudRole role, HudFields values, long ttlMs, long now) {
        check(source, role, false);
        checkLifetime(ttlMs);
        for (int field : values.fields()) {
            if (!role.carries(field)) {
                throw new IllegalArgumentException("field " + field + " is not " + role + "'s");
            }
        }
        withdrawFields(source, role);
        fields.add(new Published(source, role, values, now + ttlMs));
    }

    /** Takes away whatever {@code source} published for {@code role}, layers or fields. */
    public void withdraw(HudSource source, HudRole role) {
        withdrawFields(source, role);
        for (Iterator<HudLayer> each = layers.iterator(); each.hasNext(); ) {
            HudLayer layer = each.next();
            if (layer.source == source && layer.role == role) {
                each.remove();
            }
        }
    }

    /** Takes away everything {@code source} published. */
    public void withdraw(HudSource source) {
        for (HudRole role : HudRole.values()) {
            withdraw(source, role);
        }
    }

    /** The decisions at {@code now}; what has died by then is forgotten first. */
    public HudComposition compose(long now) {
        long nextChange = Long.MAX_VALUE;
        for (Iterator<HudLayer> each = layers.iterator(); each.hasNext(); ) {
            HudLayer layer = each.next();
            if (layer.expiresAt <= now) {
                each.remove();
            } else {
                nextChange = Math.min(nextChange, layer.expiresAt);
            }
        }
        for (Iterator<Published> each = fields.iterator(); each.hasNext(); ) {
            Published published = each.next();
            if (published.expiresAt <= now) {
                each.remove();
            } else {
                nextChange = Math.min(nextChange, published.expiresAt);
            }
        }

        EnumMap<HudRole, Published> winners = new EnumMap<>(HudRole.class);
        for (Published candidate : fields) {
            Published best = winners.get(candidate.role);
            if (best == null || candidate.role.rank(candidate.source) < best.role.rank(best.source)) {
                winners.put(candidate.role, candidate);
            }
        }
        Published navigation = winners.get(HudRole.NAVIGATION);
        HudMode mode = navigation != null && HudRole.opensNavigation(navigation.source, navigation.values)
                ? HudMode.NAVIGATION
                : HudMode.OFF;

        EnumMap<HudRole, HudSource> owners = new EnumMap<>(HudRole.class);
        EnumMap<HudRole, HudFields> values = new EnumMap<>(HudRole.class);
        EnumMap<HudWindow, List<HudLayer>> drawn = new EnumMap<>(HudWindow.class);
        if (mode == HudMode.NAVIGATION) {
            for (Published winner : winners.values()) {
                owners.put(winner.role, winner.source);
                values.put(winner.role, winner.values);
            }
            for (HudRole role : HudRole.values()) {
                if (role.layered() && role.follows == null) {
                    HudSource owner = layerOwner(role);
                    if (owner != null) {
                        owners.put(role, owner);
                    }
                }
            }
            for (HudRole role : HudRole.values()) {
                if (role.layered() && role.follows != null && owners.containsKey(role.follows)) {
                    HudSource owner = owners.get(role.follows);
                    if (hasLayer(owner, role)) {
                        owners.put(role, owner);
                    }
                }
            }
            for (HudLayer layer : layers) {
                if (owners.get(layer.role) == layer.source) {
                    List<HudLayer> list = drawn.get(layer.role.window);
                    if (list == null) {
                        list = new ArrayList<>();
                        drawn.put(layer.role.window, list);
                    }
                    list.add(layer);
                }
            }
            for (List<HudLayer> list : drawn.values()) {
                Collections.sort(list, DRAW_ORDER);
            }
        }
        for (HudWindow window : HudWindow.values()) {
            if (window.layered) {
                version(window, drawn.get(window));
            }
        }
        return new HudComposition(mode, owners, values, drawn, new EnumMap<>(versions), nextChange);
    }

    /** The source whose layers win {@code role}: the highest rank among those with a live one. */
    private HudSource layerOwner(HudRole role) {
        HudSource owner = null;
        for (HudLayer layer : layers) {
            if (layer.role == role && (owner == null || role.rank(layer.source) < role.rank(owner))) {
                owner = layer.source;
            }
        }
        return owner;
    }

    private boolean hasLayer(HudSource source, HudRole role) {
        for (HudLayer layer : layers) {
            if (layer.source == source && layer.role == role) {
                return true;
            }
        }
        return false;
    }

    /** Raises the window's version when the layers drawn in it differ from the last compose. */
    private void version(HudWindow window, List<HudLayer> drawn) {
        int count = drawn == null ? 0 : drawn.size();
        long[] serialsNow = new long[count];
        for (int index = 0; index < count; index++) {
            serialsNow[index] = drawn.get(index).serial;
        }
        long[] before = shown.get(window);
        if (!Arrays.equals(before == null ? NOTHING : before, serialsNow)) {
            Long version = versions.get(window);
            versions.put(window, version == null ? 1L : version + 1L);
        }
        shown.put(window, serialsNow);
    }

    private void withdrawFields(HudSource source, HudRole role) {
        for (Iterator<Published> each = fields.iterator(); each.hasNext(); ) {
            Published published = each.next();
            if (published.source == source && published.role == role) {
                each.remove();
            }
        }
    }

    private static void check(HudSource source, HudRole role, boolean layered) {
        if (role.layered() != layered) {
            throw new IllegalArgumentException(role + (layered ? " takes fields" : " takes layers"));
        }
        if (role.rank(source) < 0) {
            throw new IllegalArgumentException(role + " takes nothing from " + source);
        }
    }

    private static void checkLifetime(long ttlMs) {
        if (ttlMs <= 0L) {
            throw new IllegalArgumentException("lifetime " + ttlMs + " ms: everything published dies");
        }
    }

    private static final Comparator<HudLayer> DRAW_ORDER = new Comparator<HudLayer>() {
        @Override
        public int compare(HudLayer a, HudLayer b) {
            if (a.role.z != b.role.z) {
                return a.role.z < b.role.z ? -1 : 1;
            }
            return a.order < b.order ? -1 : a.order == b.order ? 0 : 1;
        }
    };

    private static final class Published {
        final HudSource source;
        final HudRole role;
        final HudFields values;
        final long expiresAt;

        Published(HudSource source, HudRole role, HudFields values, long expiresAt) {
            this.source = source;
            this.role = role;
            this.values = values;
            this.expiresAt = expiresAt;
        }
    }
}
