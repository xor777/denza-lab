package dev.denza.hudlink;

/** Where a layer lands in its window, in the window's pixels; its picture is scaled to fit. */
public final class HudRect {
    public final int x;
    public final int y;
    public final int width;
    public final int height;

    public HudRect(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("empty rectangle " + width + "×" + height);
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** The whole of a window of fixed size. */
    public static HudRect whole(HudWindow window) {
        return new HudRect(0, 0, window.width, window.height);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof HudRect)) {
            return false;
        }
        HudRect that = (HudRect) other;
        return x == that.x && y == that.y && width == that.width && height == that.height;
    }

    @Override
    public int hashCode() {
        return ((x * 31 + y) * 31 + width) * 31 + height;
    }

    @Override
    public String toString() {
        return x + "," + y + " " + width + "×" + height;
    }
}
