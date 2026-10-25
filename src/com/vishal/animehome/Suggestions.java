package com.vishal.animehome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Top 5 popular titles in India per streaming app, from JustWatch's public GraphQL API.
 * Everything (JSON and posters) is cached on disk and decoded into memory up front,
 * so showing a shelf never waits on the network.
 */
final class Suggestions {
    static final class Item {
        String title, meta, deeplink, web, posterUrl, posterFile;
        Bitmap poster;
    }

    /** App package -> JustWatch provider code (India). */
    private static final Map<String, String> CODES = new HashMap<>();
    static {
        CODES.put("com.netflix.ninja", "nfx");
        CODES.put("com.amazon.amazonvideo.livingroom", "prv");
        CODES.put("in.startv.hotstar", "jhs");
        CODES.put("com.graymatrix.did", "zee");
        CODES.put("com.sonyliv", "snl");
        CODES.put("com.mxtech.videoplayer.television", "mxp");
        CODES.put("com.fancode.tv", "fco");
    }

    private static final String API = "https://apis.justwatch.com/graphql";
    private static final String IMAGES = "https://images.justwatch.com";
    private static final long MAX_AGE_MS = 6 * 60 * 60 * 1000L;

    private final File json, posterDir;
    private final int posterW, posterH;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Map<String, List<Item>> byPackage = Collections.emptyMap();
    private volatile boolean loaded;

    Suggestions(Context c, int posterW, int posterH) {
        json = new File(c.getFilesDir(), "suggestions.json");
        posterDir = new File(c.getCacheDir(), "posters");
        posterDir.mkdirs();
        this.posterW = posterW;
        this.posterH = posterH;
    }

    List<Item> get(String pkg) {
        List<Item> l = byPackage.get(pkg);
        return l == null ? Collections.emptyList() : l;
    }

    /** Loads the cache (first call), then fetches fresh data if it is older than six hours. */
    void refresh(Runnable onReady) {
        io.execute(() -> {
            if (!loaded && json.exists()) {
                publish(read(json), onReady);
            }
            loaded = true;
            if (json.exists() && System.currentTimeMillis() - json.lastModified() < MAX_AGE_MS) return;
            try {
                String body = fetch();
                new JSONObject(body).getJSONObject("data");
                write(json, body);
                publish(body, onReady);
            } catch (Exception ignored) {
                // Offline or API hiccup: keep showing the cached shelf, try again next time home opens.
            }
        });
    }

    private void publish(String body, Runnable onReady) {
        if (body == null) return;
        Map<String, List<Item>> map = parse(body);
        Set<String> keep = new HashSet<>();
        for (List<Item> items : map.values()) {
            for (Item it : items) {
                keep.add(it.posterFile);
                it.poster = poster(it);
            }
        }
        File[] old = posterDir.listFiles();
        if (old != null) for (File f : old) if (!keep.contains(f.getName())) f.delete();
        byPackage = map;
        main.post(onReady);
    }

    private Map<String, List<Item>> parse(String body) {
        Map<String, List<Item>> map = new HashMap<>();
        try {
            JSONObject data = new JSONObject(body).getJSONObject("data");
            for (Map.Entry<String, String> e : CODES.entrySet()) {
                JSONObject block = data.optJSONObject(e.getValue());
                if (block == null) continue;
                JSONArray edges = block.getJSONArray("edges");
                List<Item> items = new ArrayList<>();
                for (int i = 0; i < edges.length() && items.size() < 5; i++) {
                    JSONObject node = edges.getJSONObject(i).getJSONObject("node");
                    JSONObject content = node.getJSONObject("content");
                    Item it = new Item();
                    it.title = content.optString("title");
                    int year = content.optInt("originalReleaseYear");
                    JSONObject scoring = content.optJSONObject("scoring");
                    double imdb = scoring == null ? 0 : scoring.optDouble("imdbScore", 0);
                    String score = imdb > 0 ? String.format("★ %.1f", imdb) : "";
                    String when = year > 0 ? String.valueOf(year) : "";
                    it.meta = score.isEmpty() || when.isEmpty() ? score + when : score + "  ·  " + when;
                    String path = content.optString("posterUrl");
                    if (path.isEmpty()) continue;
                    it.posterUrl = IMAGES + path.replace("{profile}", "s332").replace("{format}", "webp");
                    it.posterFile = path.split("/")[2] + ".webp";
                    JSONArray offers = node.optJSONArray("offers");
                    if (offers != null && offers.length() > 0) {
                        JSONObject o = offers.getJSONObject(0);
                        it.deeplink = o.isNull("deeplinkTv") ? null : o.optString("deeplinkTv");
                        it.web = o.optString("standardWebURL", null);
                    }
                    items.add(it);
                }
                if (!items.isEmpty()) map.put(e.getKey(), items);
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private Bitmap poster(Item it) {
        File f = new File(posterDir, it.posterFile);
        try {
            if (!f.exists()) {
                File tmp = new File(posterDir, it.posterFile + ".part");
                try (InputStream in = open(it.posterUrl, null); OutputStream out = new FileOutputStream(tmp)) {
                    copy(in, out);
                }
                tmp.renameTo(f);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap full = BitmapFactory.decodeFile(f.getPath(), o);
            if (full == null) return null;
            Bitmap small = Bitmap.createScaledBitmap(full, posterW, posterH, true);
            if (small != full) full.recycle();
            return small;
        } catch (Exception e) {
            return null;
        }
    }

    private String fetch() throws Exception {
        StringBuilder q = new StringBuilder("query {");
        for (String code : CODES.values()) {
            q.append(' ').append(code).append(": popularTitles(country: IN, first: 5, filter: {packages: [\"")
                .append(code).append("\"]}, sortBy: POPULAR) { edges { node { objectType ")
                .append("content(country: IN, language: \"en\") { title originalReleaseYear posterUrl scoring { imdbScore } } ")
                .append("offers(country: IN, platform: WEB, filter: {packages: [\"").append(code).append("\"]}) ")
                .append("{ standardWebURL deeplinkTv: deeplinkURL(platform: ANDROID_TV) } } } }");
        }
        q.append(" }");
        String payload = new JSONObject().put("query", q.toString()).toString();
        try (InputStream in = open(API, payload)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toString("UTF-8");
        }
    }

    private static InputStream open(String url, String postJson) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "AnimeHome/1.0 (Android TV)");
        if (postJson != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(postJson.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        return c.getInputStream();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
    }

    private static String read(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".part");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
        tmp.renameTo(f);
    }
}
