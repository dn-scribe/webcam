package io.github.dnscribe.webcam;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import io.github.dnscribe.webcam.core.HubFinder;
import io.github.dnscribe.webcam.core.Net;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;

/** Hub connection and camera settings. Camera settings apply immediately (same keys as the hub's `set` message). */
public class SettingsActivity extends Activity implements CameraService.UiListener {
    private Prefs prefs;
    private EditText host, port, token, hubName, camName;
    private CheckBox tls, auto;
    private TextView state, found;
    private LinearLayout box;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(String t) { TextView v = new TextView(this); v.setText(t); v.setTextColor(0xFF8A96A3); v.setTextSize(12); v.setPadding(0, dp(10), 0, 0); box.addView(v); return v; }
    private TextView heading(String t) { TextView v = new TextView(this); v.setText(t); v.setTextColor(Color.WHITE); v.setTextSize(17); v.setPadding(0, dp(22), 0, dp(2)); box.addView(v); return v; }
    private EditText edit(String hint, String val, boolean number) {
        EditText e = new EditText(this); e.setHint(hint); e.setText(val); e.setTextColor(Color.WHITE); e.setHintTextColor(0xFF666666); e.setSingleLine(true);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(e); return e;
    }
    private CheckBox check(String t, boolean v) { CheckBox c = new CheckBox(this); c.setText(t); c.setChecked(v); c.setTextColor(Color.WHITE); box.addView(c); return c; }
    private Button button(String t, View.OnClickListener l) { Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setOnClickListener(l); box.addView(b); return b; }

    /** A spinner bound to one camera setting; labels may differ from the stored values. */
    private void spinner(String title, final String key, final String[] values, String[] labels) {
        label(title);
        final Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        CameraService s = CameraService.get();
        String cur = s != null ? s.prefs().str(key) : prefs.str(key);
        sp.setSelection(Math.max(0, Arrays.asList(values).indexOf(cur)), false);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { applyCam(key, values[pos]); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        box.addView(sp);
    }

    private void applyCam(String key, Object v) {
        CameraService s = CameraService.get();
        try {
            JSONObject o = new JSONObject().put(key, v);
            if (s != null) s.applySetting(o); else prefs.apply(o);
        } catch (Exception ignored) { }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(0xFF0B0D10);
        box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16), dp(12), dp(16), dp(40));
        sv.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        TextView title = new TextView(this); title.setText("Webcam settings"); title.setTextColor(Color.WHITE); title.setTextSize(22); box.addView(title);
        state = new TextView(this); state.setTextColor(0xFF3DDC84); state.setPadding(0, dp(6), 0, 0); box.addView(state);

        heading("Hub (the Python app)");
        label("Hub address"); host = edit("auto-detect when empty, e.g. 192.168.1.20", prefs.hubHost(), false);
        label("Port"); port = edit("8765", String.valueOf(prefs.hubPort()), true);
        tls = check("Use TLS (wss) — the hub's default; untick for a hub started with --no-tls", prefs.hubTls());
        label("Token (only if the hub was started with one)"); token = edit("optional", prefs.hubToken(), false);
        label("Hub name (optional: pick this hub when several are on the LAN)"); hubName = edit("e.g. office", prefs.hubName(), false);
        label("Camera name (how Python finds this phone)"); camName = edit("kitchen", prefs.camName(), false);
        auto = check("Reconnect automatically", prefs.autoReconnect());
        button("Save and connect", new View.OnClickListener() { public void onClick(View v) { save(true); } });
        button("Find hub on the LAN", new View.OnClickListener() { public void onClick(View v) { findHub(); } });
        found = new TextView(this); found.setTextColor(0xFF8A96A3); box.addView(found);

        heading("Camera");
        spinner("Camera", "facing", new String[] {"environment", "user"}, new String[] {"Back", "Front"});
        spinner("Resolution (long edge)", "res", new String[] {"640x480", "1280x720", "1920x1080", "3840x2160", "max"}, new String[] {"640×480", "1280×720", "1920×1080", "3840×2160", "Maximum available"});
        spinner("Aspect ratio (photos, clips and the stream all match)", "aspect", new String[] {"native", "16:9", "4:3", "3:2", "1:1", "21:9"}, new String[] {"Camera default", "16:9", "4:3", "3:2", "1:1 square", "21:9 wide"});
        spinner("Frame rate", "fps", new String[] {"15", "24", "30", "60"}, new String[] {"15", "24", "30", "60"});
        spinner("Orientation of photos and clips", "orientation", new String[] {"auto", "portrait", "landscape", "portrait2", "landscape2"}, new String[] {"Follow the phone", "Portrait", "Landscape", "Portrait (upside down)", "Landscape (flipped)"});
        spinner("Photo size", "photoRes", new String[] {"max", "12mp", "8mp", "2mp", "video"}, new String[] {"Maximum (full sensor)", "≈ 12 MP", "≈ 8 MP", "≈ 2 MP", "Video frame size"});
        spinner("Recording quality", "bitrate", new String[] {"low", "medium", "high", "max"}, new String[] {"Low — 1 Mbps", "Medium — 2.5 Mbps", "High — 5 Mbps", "Max — 10 Mbps"});
        spinner("Recording codec", "codec", new String[] {"auto", "h264", "hevc"}, new String[] {"Auto (H.264)", "H.264", "H.265 (smaller files)"});
        CheckBox audio = check("Record the microphone with clips", prefs.bool("audio"));
        audio.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton c, boolean on) { applyCam("audio", on); } });

        heading("Keeping it alive");
        label("The webcam runs in a foreground service, so it keeps going with this app closed and the screen locked. Some phones still kill background apps: exempt it from battery optimisation (and, on Xiaomi/Huawei/Samsung, allow auto-start / “unrestricted” battery use in the system settings).");
        button("Battery optimisation…", new View.OnClickListener() { public void onClick(View v) { batteryExemption(); } });
        button("Stop the webcam (camera service)", new View.OnClickListener() { public void onClick(View v) {
            startService(new Intent(SettingsActivity.this, CameraService.class).setAction(CameraService.ACTION_STOP)); finish(); } });
        TextView ver = new TextView(this); CameraService s = CameraService.get();
        ver.setText("Version " + (s != null ? s.version() : "?")); ver.setTextColor(0xFF666666); ver.setPadding(0, dp(20), 0, 0); box.addView(ver);
    }

    private void save(boolean connect) {
        int p = 8765;
        try { p = Integer.parseInt(port.getText().toString().trim()); } catch (Exception ignored) { }
        prefs.setHub(host.getText().toString(), p, tls.isChecked(), token.getText().toString(), hubName.getText().toString(), auto.isChecked(), camName.getText().toString());
        CameraService s = CameraService.get();
        if (connect && s != null) s.reconnect();
        else if (connect) { Intent i = new Intent(this, CameraService.class); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i); }
    }

    private void findHub() {
        save(false);
        found.setText("Scanning " + Net.localPrefixes() + "…");
        final int p = prefs.hubPort(); final boolean useTls = prefs.hubTls(); final String want = prefs.hubName();
        new Thread(new Runnable() { public void run() {
            final List<HubFinder.Hub> all = new HubFinder().scanAuto(Net.localPrefixes(), p, useTls, 1500, 48, null);
            runOnUiThread(new Runnable() { public void run() {
                HubFinder.Hub pick = null; int n = 0;
                for (HubFinder.Hub h : all) if (HubFinder.nameMatch(h.name, want)) { pick = h; n++; }
                if (all.isEmpty()) found.setText("No hub found. Is it running on this Wi-Fi (port " + p + ")?");
                else if (n == 0) found.setText("Found " + all + " but none matches the hub name '" + want + "'.");
                else if (n > 1) found.setText("Several hubs found: " + all + ". Type a hub name or address.");
                else { host.setText(pick.host); tls.setChecked(pick.tls); hubName.setText(want.isEmpty() ? "" : want); found.setText("Found " + pick + " — connecting"); save(true); }
            } });
        } }, "find-hub").start();
    }

    private void batteryExemption() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isIgnoringBatteryOptimizations(getPackageName())) { found.setText("Already exempt from battery optimisation."); return; }
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
    }

    @Override protected void onStart() { super.onStart(); CameraService.UI.add(this); onServiceChanged(); }
    @Override protected void onStop() { CameraService.UI.remove(this); save(false); super.onStop(); }
    @Override public void onServiceChanged() {
        CameraService s = CameraService.get();
        state.setText(s == null ? "Camera service is not running" : s.statusText() + (s.hubTitle().isEmpty() ? "" : "  (" + s.hubTitle() + ")"));
    }
}
