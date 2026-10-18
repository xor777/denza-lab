package dev.denza.hudlink;

import java.util.ArrayList;
import java.util.List;

/** A sink that writes every call down as one line, for the relay and the host protocol tests. */
class RecordingSink implements HudRelay.Local {
    final List<String> calls = new ArrayList<>();

    @Override
    public void layer(HudSource source, String id, HudRole role, HudRect rect, HudImage image,
            float opacity, long ttlMs) {
        calls.add("layer " + source + " " + id + " " + role + " " + rect + " " + image.width + "x"
                + image.height + " " + opacity + " ttl=" + ttlMs);
    }

    @Override
    public void removeLayer(HudSource source, String id) {
        calls.add("remove " + source + " " + id);
    }

    @Override
    public void fields(HudSource source, HudRole role, HudFields fields, long ttlMs) {
        StringBuilder values = new StringBuilder();
        for (int field : fields.fields()) {
            Object value = fields.value(field);
            values.append(' ').append(field).append('=')
                    .append(value instanceof byte[] ? Pngs.hex((byte[]) value) : value);
        }
        calls.add("fields " + source + " " + role + values + " ttl=" + ttlMs);
    }

    @Override
    public void withdraw(HudSource source, HudRole role) {
        calls.add("withdraw " + source + " " + role);
    }

    @Override
    public void withdraw(HudSource source) {
        calls.add("withdraw " + source);
    }

    @Override
    public void handOver() {
        calls.add("hand over");
    }
}
