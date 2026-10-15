package io.github.dnscribe.webcam.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Photos and clips stored on the device (one folder + index.json). Same item shape as the web app's library. */
public class LibraryStore {
    public static class Item {
        public String kind;            // "photo" | "video"
        public long id;                // photo: ts*100+seq, video: unix seconds
        public String name, type;
        public long size, ts;          // ts in ms
        public double secs;
        public int w, h;
        public File file;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("kind", kind); o.put("id", id); o.put("name", name); o.put("type", type);
                o.put("size", size); o.put("ts", ts);
                if (kind.equals("video")) o.put("secs", secs);
                if (w > 0) { o.put("w", w); o.put("h", h); }
            } catch (Exception e) { throw new RuntimeException(e); }
            return o;
        }
    }

    public interface Thumbs { byte[] jpeg(File f, String kind, int width); }

    private final File dir, index;
    private final Thumbs thumbs;
    private final List<Item> items = new ArrayList<>();
    private Runnable onChange;
    private int seq;

    public LibraryStore(File dir, Thumbs thumbs) {
        this.dir = dir; this.thumbs = thumbs; this.index = new File(dir, "index.json");
        dir.mkdirs();
        load();
    }

    public void setOnChange(Runnable r) { onChange = r; }
    public File dir() { return dir; }

    private static String stamp(Date d, boolean ms) { return new SimpleDateFormat(ms ? "yyyyMMdd-HHmmss-SSS" : "yyyyMMdd-HHmmss", Locale.US).format(d); }
    public static String mime(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") ? "image/jpeg" : n.endsWith(".webm") ? "video/webm" : n.endsWith(".mp4") ? "video/mp4" : "application/octet-stream";
    }

    private synchronized void load() {
        items.clear();
        try {
            if (index.exists()) {
                JSONArray a = new JSONArray(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    File f = new File(dir, o.getString("name"));
                    if (!f.isFile()) continue;
                    Item it = new Item();
                    it.kind = o.getString("kind"); it.id = o.getLong("id"); it.name = f.getName(); it.type = mime(it.name);
                    it.size = f.length(); it.ts = o.optLong("ts", f.lastModified()); it.secs = o.optDouble("secs", 0);
                    it.w = o.optInt("w"); it.h = o.optInt("h"); it.file = f; items.add(it);
                }
            }
        } catch (Exception ignored) { }
        // files without an index entry (crash, copied in): adopt them
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName();
            boolean photo = n.startsWith("snap-") && n.endsWith(".jpg"), video = n.startsWith("rec-") && (n.endsWith(".mp4") || n.endsWith(".webm"));
            if ((!photo && !video) || findByName(n) != null) continue;
            Item it = new Item();
            it.kind = photo ? "photo" : "video"; it.name = n; it.type = mime(n); it.file = f; it.size = f.length(); it.ts = f.lastModified();
            it.id = photo ? it.ts * 100 + (seq++ % 100) : freeVideoId(it.ts / 1000);
            items.add(it);
        }
        sort();
    }

    private Item findByName(String n) { for (Item i : items) if (i.name.equals(n)) return i; return null; }
    private long freeVideoId(long id) { while (findLocked("video", id) != null) id++; return id; }
    private Item findLocked(String kind, long id) { for (Item i : items) if (i.kind.equals(kind) && i.id == id) return i; return null; }
    private void sort() { Collections.sort(items, new Comparator<Item>() { public int compare(Item a, Item b) { return Long.compare(b.ts, a.ts); } }); }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Item i : items) a.put(i.toJson());
            File tmp = new File(dir, "index.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(index)) { Files.copy(tmp.toPath(), index.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); tmp.delete(); }
        } catch (Exception ignored) { }
    }

    private void changed() { save(); Runnable r = onChange; if (r != null) r.run(); }

    public synchronized List<Item> list() { return new ArrayList<>(items); }
    public synchronized Item find(String kind, long id) { return findLocked(kind, id); }

    public synchronized JSONArray toJson() { JSONArray a = new JSONArray(); for (Item i : items) a.put(i.toJson()); return a; }

    public Item addPhoto(byte[] jpeg, int w, int h) throws IOException {
        Item it = new Item();
        synchronized (this) {
            Date now = new Date();
            it.kind = "photo"; it.ts = now.getTime(); it.id = it.ts * 100 + (seq++ % 100);
            it.name = "snap-" + stamp(now, true) + ".jpg"; it.type = "image/jpeg"; it.w = w; it.h = h;
            it.file = new File(dir, it.name);
            try (FileOutputStream o = new FileOutputStream(it.file)) { o.write(jpeg); }
            it.size = jpeg.length;
            items.add(0, it); sort();
        }
        changed();
        return it;
    }

    /** A new file name inside the library for a recorder to write to. */
    public synchronized File newVideoFile(String ext) {
        File f = new File(dir, "rec-" + stamp(new Date(), false) + "." + ext);
        int n = 1;
        while (f.exists()) f = new File(dir, "rec-" + stamp(new Date(), false) + "-" + (n++) + "." + ext);
        return f;
    }

    public Item addVideo(File f, double secs, int w, int h) {
        Item it = new Item();
        synchronized (this) {
            it.kind = "video"; it.name = f.getName(); it.type = mime(f.getName()); it.file = f; it.size = f.length();
            it.ts = System.currentTimeMillis(); it.id = freeVideoId(it.ts / 1000); it.secs = secs; it.w = w; it.h = h;
            items.add(0, it); sort();
        }
        changed();
        return it;
    }

    public boolean delete(String kind, long id) {
        synchronized (this) {
            Item it = findLocked(kind, id);
            if (it == null) return false;
            items.remove(it); it.file.delete();
        }
        changed();
        return true;
    }

    public String thumbDataUrl(Item it) {
        byte[] j = thumbs == null ? null : thumbs.jpeg(it.file, it.kind, 240);
        return j == null ? null : "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(j);
    }
}
