package io.github.dnscribe.webcam.core;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What a recording knows about itself, for lining it up with another clock (a printer's log): the phone's wall clock and monotonic clock at the
 * first frame, and the annotations ("marks") the hub sent while it ran. Written beside the clip as <clip>.meta.json when the clip is finished.
 * Pure Java (the clocks are passed in).
 */
public class RecMeta {
    private boolean begun, started;
    private String label = "";
    private long startWallMs, startMonoNs;
    private JSONArray marks = new JSONArray();

    public static File sidecar(File video) { return new File(video.getPath() + ".meta.json"); }

    public static JSONObject read(File video) {
        try {
            File f = sidecar(video);
            return f.isFile() ? new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)) : null;
        } catch (Exception e) { return null; }
    }

    public synchronized void begin(String label) { clear(); begun = true; this.label = label == null ? "" : label; }

    /** The recorder delivered its first frame (call with the clocks read at that moment). */
    public synchronized void started(long wallMs, long monoNs) { if (begun) { started = true; startWallMs = wallMs; startMonoNs = monoNs; } }

    public synchronized boolean recording() { return begun; }

    public synchronized void clear() { begun = started = false; label = ""; startWallMs = startMonoNs = 0; marks = new JSONArray(); }

    /** An annotation from the hub; the reply says whether it landed in a recording. */
    public synchronized JSONObject mark(String text, double hostT, long wallMs, long monoNs) {
        JSONObject r = new JSONObject();
        try {
            r.put("recording", begun); r.put("phone_wall_ms", wallMs); r.put("phone_mono_ns", monoNs);
            if (started) r.put("rec_ms", (monoNs - startMonoNs) / 1e6);
            if (begun) marks.put(new JSONObject().put("text", text).put("host_t", hostT).put("phone_wall_ms", wallMs).put("phone_mono_ns", monoNs));
        } catch (Exception e) { throw new RuntimeException(e); }
        return r;
    }

    /** The live part, for rec_status: null before the first frame. */
    public synchronized JSONObject live() {
        if (!started) return null;
        try { return new JSONObject().put("start_wall_ms", startWallMs).put("start_mono_ns", startMonoNs).put("label", label); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** The recording ended: the full record, saved beside `video` (when not null). Returns it. */
    public synchronized JSONObject finish(File video, double secs) {
        JSONObject o = new JSONObject();
        try {
            JSONArray out = new JSONArray();
            for (int i = 0; i < marks.length(); i++) {
                JSONObject m = marks.getJSONObject(i);
                if (started) m.put("rec_ms", (m.getLong("phone_mono_ns") - startMonoNs) / 1e6);
                out.put(m);
            }
            o.put("label", label); o.put("started", started).put("start_wall_ms", startWallMs).put("start_mono_ns", startMonoNs);
            o.put("duration_ms", Math.round(secs * 1000)); o.put("marks", out);
            if (video != null) {
                o.put("name", video.getName());
                try (FileOutputStream f = new FileOutputStream(sidecar(video))) { f.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        clear();
        return o;
    }
}
