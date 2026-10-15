package io.github.dnscribe.webcam;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/** Camera settings (same names as the web app / hub `set` message) + hub connection settings. */
public class Prefs {
    /** Settings that need the camera session rebuilt when they change. */
    public static final String[] RESTART_KEYS = {"facing", "deviceId", "res", "aspect", "fps", "audio", "orientation"};
    private static final String[][] DEFAULTS = {
        {"facing", "environment"}, {"deviceId", ""}, {"res", "1280x720"}, {"aspect", "native"}, {"fps", "30"}, {"audio", "false"},
        {"bitrate", "medium"}, {"codec", "auto"}, {"photoRes", "max"}, {"streamFps", "10"}, {"streamWidth", "640"},
        {"streamQuality", "0.6"}, {"snapQuality", "0.92"}, {"zoom", "1.0"}, {"torch", "false"}, {"exposureCompensation", "0"},
        {"orientation", "auto"}};
    private static final String[] NUMERIC = {"fps", "streamFps", "streamWidth", "streamQuality", "snapQuality", "zoom", "exposureCompensation"};
    private static final String[] BOOLEAN = {"audio", "torch"};

    private final SharedPreferences sp;

    public Prefs(Context c) { sp = c.getSharedPreferences("webcam", Context.MODE_PRIVATE); }

    private static boolean in(String[] a, String k) { for (String s : a) if (s.equals(k)) return true; return false; }

    private String def(String k) { for (String[] d : DEFAULTS) if (d[0].equals(k)) return d[1]; return null; }

    public synchronized String str(String k) { return sp.getString("cam." + k, def(k)); }
    public synchronized double num(String k) { try { return Double.parseDouble(str(k)); } catch (Exception e) { return Double.parseDouble(def(k)); } }
    public int i(String k) { return (int) Math.round(num(k)); }
    public boolean bool(String k) { return "true".equals(str(k)); }

    /** Current camera settings as the `settings` object of the state message. */
    public synchronized JSONObject camJson() {
        JSONObject o = new JSONObject();
        try {
            for (String[] d : DEFAULTS) {
                String k = d[0];
                if (in(BOOLEAN, k)) o.put(k, bool(k)); else if (in(NUMERIC, k)) o.put(k, num(k)); else o.put(k, str(k));
            }
        } catch (JSONException e) { throw new RuntimeException(e); }
        return o;
    }

    /** Apply a partial settings object; returns the keys whose value changed. Unknown keys are ignored. */
    public synchronized List<String> apply(JSONObject p) {
        List<String> changed = new ArrayList<>();
        try {
            if (p.has("width") && p.has("height")) p.put("res", p.optInt("width") + "x" + p.optInt("height"));
            SharedPreferences.Editor ed = sp.edit();
            for (Iterator<String> it = p.keys(); it.hasNext();) {
                String k = it.next();
                if (def(k) == null) continue;
                String v;
                Object raw = p.get(k);
                if (in(BOOLEAN, k)) v = String.valueOf(raw instanceof Boolean ? (Boolean) raw : "true".equals(String.valueOf(raw)));
                else if (in(NUMERIC, k)) v = String.valueOf(Double.parseDouble(String.valueOf(raw)));
                else v = String.valueOf(raw);
                if (!v.equals(sp.getString("cam." + k, def(k)))) { ed.putString("cam." + k, v); changed.add(k); }
            }
            ed.apply();
        } catch (Exception ignored) { }
        return changed;
    }

    public boolean needsRestart(List<String> changed) { for (String k : changed) if (in(RESTART_KEYS, k)) return true; return false; }

    // ---- hub connection ----
    public String hubHost() { return sp.getString("hub.host", ""); }
    public int hubPort() { return sp.getInt("hub.port", 8765); }
    public boolean hubTls() { return sp.getBoolean("hub.tls", true); }
    public String hubToken() { return sp.getString("hub.token", ""); }
    public String hubName() { return sp.getString("hub.name", ""); }          // optional: pick this hub when scanning
    public boolean autoReconnect() { return sp.getBoolean("hub.auto", true); }
    public String camName() {
        String n = sp.getString("cam.name", "");
        if (n.isEmpty()) { n = "android-" + Integer.toString((int) (Math.random() * 46656), 36); sp.edit().putString("cam.name", n).apply(); }
        return n;
    }
    public void setHub(String host, int port, boolean tls, String token, String hubName, boolean auto, String camName) {
        sp.edit().putString("hub.host", host.trim()).putInt("hub.port", port).putBoolean("hub.tls", tls).putString("hub.token", token.trim())
                .putString("hub.name", hubName.trim()).putBoolean("hub.auto", auto).putString("cam.name", camName.trim()).apply();
    }
    public void setHubHost(String host, int port, boolean tls) { sp.edit().putString("hub.host", host).putInt("hub.port", port).putBoolean("hub.tls", tls).apply(); }
    public boolean serviceWanted() { return sp.getBoolean("service.wanted", true); }
    public void setServiceWanted(boolean b) { sp.edit().putBoolean("service.wanted", b).apply(); }
}
