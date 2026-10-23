package dev.denza.hud.probe;

/**
 * SVG path data to moves, lines and curves: enough for the wide figures of Luminofor's spec.json
 * (M L H V Q A Z, absolute). Arcs become cubic Béziers of at most a quarter turn each, as browsers
 * draw them. Plain Java, no android.graphics: the overlay feeds the result into a Path.
 */
final class AdasSvgPath {
    interface Sink {
        void moveTo(float x, float y);
        void lineTo(float x, float y);
        void quadTo(float x1, float y1, float x, float y);
        void cubicTo(float x1, float y1, float x2, float y2, float x, float y);
        void close();
    }

    static void parse(String d, Sink out) {
        int[] at = {0};
        char cmd = 0;
        float x = 0, y = 0, sx = 0, sy = 0;
        while (true) {
            skip(d, at);
            if (at[0] >= d.length()) return;
            char c = d.charAt(at[0]);
            if (Character.isLetter(c)) { cmd = c; at[0]++; }
            else if (cmd == 0) throw new IllegalArgumentException("Path data without a command");
            switch (cmd) {
                case 'M': x = sx = num(d, at); y = sy = num(d, at); out.moveTo(x, y); cmd = 'L'; break;
                case 'L': x = num(d, at); y = num(d, at); out.lineTo(x, y); break;
                case 'H': x = num(d, at); out.lineTo(x, y); break;
                case 'V': y = num(d, at); out.lineTo(x, y); break;
                case 'Q': { float x1 = num(d, at), y1 = num(d, at); x = num(d, at); y = num(d, at); out.quadTo(x1, y1, x, y); break; }
                case 'A': {
                    float rx = num(d, at), ry = num(d, at), rot = num(d, at);
                    boolean large = num(d, at) != 0, sweep = num(d, at) != 0;
                    float ex = num(d, at), ey = num(d, at);
                    arc(out, x, y, rx, ry, rot, large, sweep, ex, ey);
                    x = ex; y = ey; break;
                }
                case 'Z': case 'z': out.close(); x = sx; y = sy; cmd = 0; break;
                default: throw new IllegalArgumentException("Unsupported path command " + cmd);
            }
        }
    }

    private static void skip(String d, int[] at) {
        while (at[0] < d.length() && (d.charAt(at[0]) == ' ' || d.charAt(at[0]) == ',')) at[0]++;
    }

    private static float num(String d, int[] at) {
        skip(d, at);
        int start = at[0];
        if (at[0] < d.length() && (d.charAt(at[0]) == '-' || d.charAt(at[0]) == '+')) at[0]++;
        while (at[0] < d.length() && (Character.isDigit(d.charAt(at[0])) || d.charAt(at[0]) == '.')) at[0]++;
        if (start == at[0]) throw new IllegalArgumentException("Number expected at " + start);
        return Float.parseFloat(d.substring(start, at[0]));
    }

    /** SVG 1.1 F.6.5: endpoint to centre parameterisation, then quarter-turn cubic pieces. */
    static void arc(Sink out, float x1, float y1, float rx, float ry, float rotation, boolean large, boolean sweep, float x2, float y2) {
        if (x1 == x2 && y1 == y2) return;
        if (rx == 0 || ry == 0) { out.lineTo(x2, y2); return; }
        double phi = Math.toRadians(rotation), cos = Math.cos(phi), sin = Math.sin(phi);
        double dx = (x1 - x2) / 2.0, dy = (y1 - y2) / 2.0;
        double xp = cos * dx + sin * dy, yp = -sin * dx + cos * dy;
        double arx = Math.abs(rx), ary = Math.abs(ry);
        double lambda = xp * xp / (arx * arx) + yp * yp / (ary * ary);
        if (lambda > 1) { arx *= Math.sqrt(lambda); ary *= Math.sqrt(lambda); }
        double num = arx * arx * ary * ary - arx * arx * yp * yp - ary * ary * xp * xp;
        double den = arx * arx * yp * yp + ary * ary * xp * xp;
        double k = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
        double cxp = k * arx * yp / ary, cyp = -k * ary * xp / arx;
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0, cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;
        double t1 = angle(1, 0, (xp - cxp) / arx, (yp - cyp) / ary);
        double dt = angle((xp - cxp) / arx, (yp - cyp) / ary, (-xp - cxp) / arx, (-yp - cyp) / ary);
        if (!sweep && dt > 0) dt -= 2 * Math.PI;
        else if (sweep && dt < 0) dt += 2 * Math.PI;
        int pieces = (int) Math.ceil(Math.abs(dt) / (Math.PI / 2) - 1e-9);
        double step = dt / pieces, alpha = 4.0 / 3.0 * Math.tan(step / 4);
        double t = t1;
        for (int i = 0; i < pieces; i++) {
            double c0 = Math.cos(t), s0 = Math.sin(t), c1 = Math.cos(t + step), s1 = Math.sin(t + step);
            double[] p1 = point(cx, cy, arx, ary, cos, sin, c0 - alpha * s0, s0 + alpha * c0);
            double[] p2 = point(cx, cy, arx, ary, cos, sin, c1 + alpha * s1, s1 - alpha * c1);
            double[] p = i == pieces - 1 ? new double[]{x2, y2} : point(cx, cy, arx, ary, cos, sin, c1, s1);
            out.cubicTo((float) p1[0], (float) p1[1], (float) p2[0], (float) p2[1], (float) p[0], (float) p[1]);
            t += step;
        }
    }

    private static double[] point(double cx, double cy, double rx, double ry, double cos, double sin, double u, double v) {
        double x = rx * u, y = ry * v;
        return new double[]{cx + cos * x - sin * y, cy + sin * x + cos * y};
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double a = Math.atan2(uy, ux), b = Math.atan2(vy, vx), d = b - a;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return a == b ? 0 : d;
    }
}
