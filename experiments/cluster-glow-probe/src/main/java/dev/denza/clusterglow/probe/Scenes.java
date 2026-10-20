package dev.denza.clusterglow.probe;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** What tools/cluster_glow_scenes.py wrote into the assets: the scenes and the glow's geometry. */
final class Scenes {

    /** The numbers luminofor.js draws the glow with, read from spec.json by the tool. */
    static final class Geometry {
        final float W;
        final float H;
        final float axis;
        final float stockBottom;
        final float glowMax;
        final float glowFullKw;
        final float glowRadius;
        final float glowReachBelow;
        final int ink;
        final int blue;

        Geometry(JSONObject o) throws Exception {
            JSONObject glow = o.getJSONObject("glow");
            W = (float) o.getDouble("W");
            H = (float) o.getDouble("H");
            axis = (float) o.getDouble("axis");
            stockBottom = (float) o.getDouble("stockBottom");
            glowMax = (float) glow.getDouble("max");
            glowFullKw = (float) glow.getDouble("fullKw");
            glowRadius = (float) glow.getDouble("radius");
            glowReachBelow = (float) glow.getDouble("reachBelow");
            ink = Color.parseColor(o.getString("ink"));
            blue = Color.parseColor(o.getString("blue"));
        }
    }

    static final class Scene {
        final String board;
        final float powerKw;

        Scene(String board, float powerKw) {
            this.board = board;
            this.powerKw = powerKw;
        }
    }

    final Geometry geometry;
    final List<Scene> scenes = new ArrayList<>();
    private final AssetManager assets;

    Scenes(AssetManager assets) throws Exception {
        this.assets = assets;
        JSONObject root = new JSONObject(read(assets, "scenes.json"));
        geometry = new Geometry(root.getJSONObject("geometry"));
        JSONArray list = root.getJSONArray("scenes");
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.getJSONObject(i);
            scenes.add(new Scene(s.getString("board"), (float) s.getDouble("powerKw")));
        }
    }

    Bitmap picture(Scene scene) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        try (InputStream in = assets.open("scenes/" + scene.board + ".png")) {
            return BitmapFactory.decodeStream(in, null, options);
        }
    }

    private static String read(AssetManager assets, String name) throws IOException {
        try (InputStream in = assets.open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
