package dev.denza.adaslink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Exact envelope of a successful in-process vector decode, not a file loader. */
public final class VectorTilePacket {
    public final long sequence, threadKey;
    public final VectorRoadTile.TileId wrapper, render, source;
    public final byte[] protobuf;

    private VectorTilePacket(long sequence, long threadKey, VectorRoadTile.TileId wrapper,
                             VectorRoadTile.TileId render, VectorRoadTile.TileId source, byte[] protobuf) {
        this.sequence = sequence; this.threadKey = threadKey;
        this.wrapper = wrapper; this.render = render; this.source = source; this.protobuf = protobuf;
    }
    public static VectorTilePacket decode(byte[] bytes) {
        if (bytes == null || bytes.length <= 72 || bytes.length > 72 + 2 * 1024 * 1024)
            throw new IllegalArgumentException("vector packet byte bounds");
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getInt() != 0x31545659 || in.getShort() != 1 || in.getShort() != 72
                || in.getInt() != bytes.length - 72 || in.getInt() != 1)
            throw new IllegalArgumentException("vector packet identity or native success missing");
        long sequence = in.getLong(), threadKey = in.getLong();
        VectorRoadTile.TileId wrapper = tile(in), render = tile(in), source = tile(in);
        if (in.getInt() != 0 || !wrapper.equals(render) || !wrapper.equals(source))
            throw new IllegalArgumentException("vector tile contexts disagree");
        return new VectorTilePacket(sequence, threadKey, wrapper, render, source,
                Arrays.copyOfRange(bytes, 72, bytes.length));
    }
    private static VectorRoadTile.TileId tile(ByteBuffer in) {
        return new VectorRoadTile.TileId(in.getInt(), in.getInt(), in.getInt());
    }
}
