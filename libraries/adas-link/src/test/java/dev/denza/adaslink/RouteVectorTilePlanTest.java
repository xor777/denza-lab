package dev.denza.adaslink;

import java.util.*;

/** Native ellipsoid oracle coordinates, moving/full-route budgets, dateline and route ownership. */
public final class RouteVectorTilePlanTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static void bad(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { checks++; return; } throw new AssertionError("invalid input accepted"); }
    static void projection() {
        // Independent Python native-formula oracle: wgs84_to_world, floor(world/2^(30-15)).
        double[][] oracle = {{0,0,16384,16384},{49.12,55.8,20855,10265},{37.06,55.5,19757,10313},
                {-73,40,9739,12427},{151,-33.8,30128,19636},{179.99,10,32767,15475},
                {-179.99,10,0,15475},{0,85,16384,88},{0,-85,16384,32679},{180,90,32767,0},{-180,-90,0,32767}};
        for (double[] o : oracle) {
            VectorRoadTile.TileId t = RouteVectorTilePlan.tile(o[0], o[1], 15);
            check(t.x == (int) o[2] && t.y == (int) o[3], "native ellipsoid tile, not spherical XYZ");
            RouteVectorTilePlan.Center c = RouteVectorTilePlan.tileCenter(t);
            check(RouteVectorTilePlan.tile(c.lon, c.lat, t.z).equals(t), "tile center inverse roundtrip");
            VectorRoadTile.TileId lower = RouteVectorTilePlan.tile(o[0], o[1], 14);
            check(lower.x == t.x / 2 && lower.y == t.y / 2, "z14 native parent");
        }
        bad(() -> RouteVectorTilePlan.tile(Double.NaN, 0, 15)); bad(() -> RouteVectorTilePlan.tile(0, 91, 15));
        bad(() -> RouteVectorTilePlan.tile(181, 0, 15)); bad(() -> RouteVectorTilePlan.tile(0, 0, 31));
    }
    static void corridor() {
        double[] lon = {49.1,49.11,49.12,49.13,49.14}, lat = {55.8,55.8,55.81,55.8,55.8};
        RouteVectorTilePlan p = new RouteVectorTilePlan(lon, lat);
        RouteVectorTilePlan.Window initial = p.window(0, Collections.emptySet());
        check(!initial.required.isEmpty() && initial.required.size() <= 64 && initial.samples <= 2048, "initial window bounded");
        check(initial.missing.size() == initial.required.size(), "missing reported, not claimed loaded");
        check(initial.required.get(0).equals(RouteVectorTilePlan.tile(lon[0],lat[0],15)), "near progress first");
        RouteVectorTilePlan.Window observed = p.window(0, new HashSet<>(initial.required));
        check(observed.missing.isEmpty(), "actual observed tile membership used");
        RouteVectorTilePlan.Window next = p.window(3.5, Collections.emptySet());
        check(next.fromMetres > 0 && next.required.get(0).equals(RouteVectorTilePlan.tile(49.135,55.8,15)), "window advances from actual scalar");
        RouteVectorTilePlan.Window end = p.window(4, Collections.emptySet());
        check(end.fromMetres == end.toMetres && end.samples == 1, "final vertex supported");
        lon[0] = -10; lat[0] = -80;
        check(p.window(0,Collections.emptySet()).required.equals(initial.required), "immutable route copy");
        try { initial.required.clear(); throw new AssertionError("mutable window"); } catch (UnsupportedOperationException expected) { checks++; }
        RouteVectorTilePlan capped = new RouteVectorTilePlan(new double[]{0,1},new double[]{0,0},new RouteVectorTilePlan.Config(15,50000,100,1,9,1000));
        RouteVectorTilePlan.Window cap = capped.window(0,Collections.emptySet());
        check(cap.truncated && cap.toMetres < cap.requestedToMetres && cap.required.size() <= 9, "tile cap explicit truncation");
        RouteVectorTilePlan sampleCap = new RouteVectorTilePlan(new double[]{0,.1},new double[]{0,0},new RouteVectorTilePlan.Config(14,5000,10,0,256,3));
        check(sampleCap.window(0,Collections.emptySet()).truncated && sampleCap.window(0,Collections.emptySet()).samples == 3, "sample work cap");
        RouteVectorTilePlan dateline = new RouteVectorTilePlan(new double[]{179.999,-179.999},new double[]{10,10});
        RouteVectorTilePlan.Window crossing = dateline.window(0,Collections.emptySet());
        check(crossing.requestedToMetres < 300 && !crossing.truncated, "short dateline path, not globe-spanning");
        boolean east = false, west = false;
        for (VectorRoadTile.TileId id : crossing.required) { east |= id.x == 32767; west |= id.x == 0; }
        check(east && west, "neighbors wrap at dateline");
        RouteVectorTilePlan pole = new RouteVectorTilePlan(new double[]{0,.001},new double[]{90,90});
        for (VectorRoadTile.TileId id : pole.window(0,Collections.emptySet()).required) check(id.y >= 0 && id.y < (1 << id.z), "polar neighbors clipped");
        RouteVectorTilePlan dense = new RouteVectorTilePlan(new double[]{0,0,0,.00001},new double[]{0,0,0,0});
        check(dense.window(0,Collections.emptySet()).samples > 0, "repeated vertices terminate");
        bad(() -> p.window(-1,Collections.emptySet())); bad(() -> p.window(Double.NaN,Collections.emptySet())); bad(() -> p.window(5,Collections.emptySet()));
        bad(() -> p.window(0,null)); bad(() -> new RouteVectorTilePlan(new double[]{0},new double[]{0}));
        bad(() -> new RouteVectorTilePlan.Config(13,1000,10,1,64,100)); bad(() -> new RouteVectorTilePlan.Config(15,1000,0,1,64,100));
    }
    static void wholeRoute() {
        // This line is >100 km: wholeRoute must not inherit either the moving
        // window's 1 km horizon or its smaller work/tile caps. Source-only test.
        RouteVectorTilePlan p = new RouteVectorTilePlan(new double[]{0,.5,1},new double[]{0,0,0},
                new RouteVectorTilePlan.Config(15,1000,120,1,9,1));
        RouteVectorTilePlan.Window moving = p.window(0,Collections.emptySet());
        RouteVectorTilePlan.Window all = p.wholeRoute(4096,10000);
        check(moving.truncated && moving.requestedToMetres == 1000, "moving policy remains independent");
        check(!all.truncated && all.fromMetres == 0 && all.requestedToMetres > 100000,
                "whole route extends beyond 50 km without moving horizon");
        check(all.toMetres == all.requestedToMetres && all.samples > 800,
                "whole route reaches its real final vertex");
        check(all.required.contains(RouteVectorTilePlan.tile(0,0,15)) &&
                all.required.contains(RouteVectorTilePlan.tile(1,0,15)), "both route endpoints planned");
        check(all.missing.equals(all.required), "planning does not claim tiles observed");
        check(all.required.size() <= 4096 && all.samples <= 10000, "whole-route bounds respected");
        RouteVectorTilePlan.Window tiles = p.wholeRoute(24,10000);
        check(tiles.truncated && tiles.required.size() <= 24 && tiles.toMetres < tiles.requestedToMetres,
                "whole-route tile cap explicitly leaves incomplete tail");
        RouteVectorTilePlan.Window samples = p.wholeRoute(4096,3);
        check(samples.truncated && samples.samples == 3 && samples.toMetres < samples.requestedToMetres,
                "whole-route sample cap explicitly leaves incomplete tail");
        RouteVectorTilePlan repeated = new RouteVectorTilePlan(new double[]{49.1,49.1,49.11,49.11},
                new double[]{55.8,55.8,55.8,55.8});
        RouteVectorTilePlan.Window repeatedAll = repeated.wholeRoute(64,1000);
        RouteVectorTilePlan.Window plain = new RouteVectorTilePlan(new double[]{49.1,49.11},
                new double[]{55.8,55.8}).wholeRoute(64,1000);
        check(!repeatedAll.truncated && repeatedAll.toMetres == repeatedAll.requestedToMetres &&
                repeatedAll.required.equals(plain.required), "repeated first/final vertices preserve complete tile plan");
        RouteVectorTilePlan.Window zero = new RouteVectorTilePlan(new double[]{49.1,49.1,49.1},
                new double[]{55.8,55.8,55.8}).wholeRoute(9,1);
        check(!zero.truncated && zero.samples == 1 && zero.fromMetres == 0 && zero.toMetres == 0,
                "zero-length whole route terminates with one endpoint observation");
        RouteVectorTilePlan.Window crossing = new RouteVectorTilePlan(new double[]{179.99,-179.99},
                new double[]{10,10}).wholeRoute(64,1000);
        boolean east = false, west = false;
        for (VectorRoadTile.TileId id : crossing.required) { east |= id.x == 32767; west |= id.x == 0; }
        check(!crossing.truncated && crossing.toMetres < 3000 && east && west,
                "whole route follows short dateline crossing and wraps neighbors");
        RouteVectorTilePlan.Window north = new RouteVectorTilePlan(new double[]{0,.1},new double[]{89,89}).wholeRoute(64,1000);
        check(!north.truncated && north.required.contains(RouteVectorTilePlan.tile(.025,89,15))
                && north.required.contains(RouteVectorTilePlan.tile(.075,89,15)),
                "sample step scales with latitude instead of skipping narrow tile columns");
        try { all.missing.clear(); throw new AssertionError("mutable full-route window"); }
        catch (UnsupportedOperationException expected) { checks++; }
        bad(() -> p.wholeRoute(0,1)); bad(() -> p.wholeRoute(4097,1));
        bad(() -> p.wholeRoute(1,0)); bad(() -> p.wholeRoute(1,500001));
    }
    public static void main(String[] args) { projection(); corridor(); wholeRoute(); System.out.println("RouteVectorTilePlanTest: " + checks + " checks passed"); }
}
