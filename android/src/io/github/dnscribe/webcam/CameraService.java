package io.github.dnscribe.webcam;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.OrientationEventListener;
import io.github.dnscribe.webcam.core.Device;
import io.github.dnscribe.webcam.core.HubFinder;
import io.github.dnscribe.webcam.core.HubSession;
import io.github.dnscribe.webcam.core.LibraryStore;
import io.github.dnscribe.webcam.core.Net;
import io.github.dnscribe.webcam.core.RecMeta;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The webcam itself. A foreground service (camera + microphone types) that owns the camera, the library and the hub connection, so it keeps
 * working with the app in the background and the screen locked. A partial wake lock and a Wi-Fi lock keep the CPU and the radio awake.
 */
public class CameraService extends Service {
    public static final String ACTION_STOP = "io.github.dnscribe.webcam.STOP";
    private static final String CHANNEL = "webcam";
    private static final int NOTIF_ID = 1;

    public interface UiListener { void onServiceChanged(); }

    public static final List<UiListener> UI = new CopyOnWriteArrayList<>();
    private static volatile CameraService instance;
    public static CameraService get() { return instance; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private LibraryStore lib;
    private CameraController cam;
    private HubSession hub;
    private ServiceDevice device;
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wifi;
    private OrientationEventListener oel;
    private volatile String status = "off", statusText = "starting…", hubTitle = "", lastError = "";
    private volatile LibraryStore.Item lastRec;
    private final RecMeta meta = new RecMeta();          // clocks and marks of the recording in progress
    private final AtomicBoolean finding = new AtomicBoolean();
    private boolean started;
    private String versionName = "0.0.0";

    // ------------------------------------------------------------------ accessors for the UI
    public Prefs prefs() { return prefs; }
    public LibraryStore library() { return lib; }
    public CameraController camera() { return cam; }
    public HubSession hub() { return hub; }
    public Device device() { return device; }
    public String status() { return status; }
    public String statusText() { return statusText; }
    public String hubTitle() { return hubTitle; }
    public String lastError() { return lastError; }
    public String version() { return versionName; }

    private void fire() {
        main.post(new Runnable() { public void run() { for (UiListener l : UI) l.onServiceChanged(); } });
    }

    // ------------------------------------------------------------------ lifecycle
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        try { versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        File base = getExternalFilesDir(null);
        lib = new LibraryStore(new File(base != null ? base : getFilesDir(), "library"), new AndroidThumbs());
        device = new ServiceDevice();
        hub = new HubSession(device, new HubSession.Listener() {
            public void onStatus(String s, String text) { status = s; statusText = text; updateNotification(); fire(); }
            public void onWelcome(String camName, String hubName) { hubTitle = hubName; fire(); }
        });
        lib.setOnChange(new Runnable() { public void run() { hub.notifyLibrary(false); fire(); } });
        cam = new CameraController(this, prefs, lib, new CameraController.Callback() {
            public void onChanged() { hub.sendState(); fire(); }
            public void onError(String msg) { lastError = msg; hub.notifyError(msg); fire(); }
            public void onFrame(byte[] jpeg) { hub.sendFrame(jpeg); }
            public boolean canSendFrame() { return hub.canSendFrame(); }
            public void onRecordingStarted(long wallMs, long monoNs) { meta.started(wallMs, monoNs); hub.sendRecStatus(); }
            public void onRecordingFinished(File f, double secs, int w, int h, String error) {
                meta.finish(f, secs);                     // the record beside the clip (nothing when the recording failed)
                if (f != null) lastRec = lib.addVideo(f, secs, w, h); else { lastError = error; hub.notifyError(error); }
                hub.sendRecStatus(); fire();
            }
        });
        oel = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN || !"auto".equals(prefs.str("orientation"))) return;
                int r = ((o + 45) / 90 * 90) % 360;
                if (r != cam.rotation()) { cam.setRotation(r); fire(); }
            }
        };
        if (oel.canDetectOrientation()) oel.enable();
        applyOrientation();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webcam:service");
        wl.setReferenceCounted(false);
        wl.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifi = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "webcam:wifi");
        wifi.setReferenceCounted(false);
        wifi.acquire();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { prefs.setServiceWanted(false); stopSelf(); return START_NOT_STICKY; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { statusText = "camera permission needed"; fire(); stopSelf(); return START_NOT_STICKY; }
        prefs.setServiceWanted(true);
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIF_ID, n, type);
        } else startForeground(NOTIF_ID, n);
        if (!started) { started = true; cam.start(); startHub(); main.postDelayed(watchdog, 5000); main.postDelayed(recTick, 1000); }
        else cam.start();         // already running (the app was reopened): only make sure the camera is open - never restart it, that would end a recording
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) { /* keep running: that is the point */ super.onTaskRemoved(rootIntent); }

    @Override public void onDestroy() {
        instance = null;
        main.removeCallbacksAndMessages(null);
        if (oel != null) oel.disable();
        if (hub != null) hub.shutdown();
        if (cam != null) cam.release();
        if (wl != null && wl.isHeld()) wl.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        fire();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ hub connection
    private void startHub() {
        if (prefs.hubHost().isEmpty()) { discover(); return; }
        HubSession.Config c = new HubSession.Config();
        c.host = prefs.hubHost(); c.port = prefs.hubPort(); c.tls = prefs.hubTls(); c.token = prefs.hubToken();
        c.name = prefs.camName(); c.auto = prefs.autoReconnect(); c.version = versionName;
        hub.start(c);
    }

    /** Called by the settings screen after the hub fields changed. */
    public void reconnect() { startHub(); }

    /** Look for a hub on the LAN (optionally matching the configured hub name) and connect to it. */
    public void discover() {
        if (!finding.compareAndSet(false, true)) return;
        statusText = "looking for a hub on the LAN…"; status = "wait"; fire();
        new Thread(new Runnable() { public void run() {
            try {
                List<HubFinder.Hub> found = new HubFinder().scanAuto(Net.localPrefixes(), prefs.hubPort(), prefs.hubTls(), 1500, 48, null);
                HubFinder.Hub pick = null; int n = 0;
                for (HubFinder.Hub h : found) if (HubFinder.nameMatch(h.name, prefs.hubName())) { pick = h; n++; }
                if (pick == null || n != 1) {
                    status = "off"; statusText = found.isEmpty() ? "no hub found on the LAN (scanning again soon)" : n > 1 ? "several hubs found - set a hub name in settings" : "no hub named '" + prefs.hubName() + "' found";
                    fire(); updateNotification();
                } else {
                    prefs.setHubHost(pick.host, pick.port, pick.tls);
                    main.post(new Runnable() { public void run() { startHub(); } });
                }
            } finally { finding.set(false); }
        } }, "hub-finder").start();
    }

    /** Every 15 s: look for the hub again if we have none, or if the saved one has been unreachable for a while (its IP may have changed). */
    private final Runnable watchdog = new Runnable() { public void run() {
        if (!hub.isConnected() && (prefs.hubHost().isEmpty() || hub.failures() >= 3)) discover();
        main.postDelayed(this, 15000);
    } };

    private final Runnable recTick = new Runnable() { public void run() {
        if (cam.isRecording()) { hub.sendRecStatus(); fire(); }
        main.postDelayed(this, 1000);
    } };

    // ------------------------------------------------------------------ settings / orientation
    private void applyOrientation() {
        switch (prefs.str("orientation")) {
            case "portrait": cam.setRotation(0); break;
            case "landscape": cam.setRotation(270); break;
            case "portrait2": cam.setRotation(180); break;
            case "landscape2": cam.setRotation(90); break;
            default: break;      // auto: the sensor listener drives it
        }
    }

    /** Settings from the UI (same keys as the hub's `set` message). Returns an error message or null. */
    public String applySetting(JSONObject s) { return device.set(s); }

    // ------------------------------------------------------------------ notification
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Webcam", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while the phone is acting as a webcam");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CameraService.class).setAction(ACTION_STOP), flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Webcam " + (status.equals("on") ? "streaming" : "running")).setContentText(statusText)
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build());
        return b.build();
    }

    private void updateNotification() {
        if (!started) return;
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification()); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ the Device the hub protocol talks to
    private final class ServiceDevice implements Device {
        @Override public JSONObject state() {
            JSONObject s = new JSONObject();
            try {
                s.put("version", versionName); s.put("build", "android"); s.put("platform", "android");
                s.put("settings", prefs.camJson()); s.put("caps", cam.caps()); s.put("devices", cam.devices());
                s.put("video", cam.video()); s.put("recording", recStatus()); s.put("streaming", cam.isStreaming());
            } catch (JSONException e) { throw new RuntimeException(e); }
            return s;
        }

        @Override public String set(JSONObject settings) {
            List<String> changed = prefs.apply(settings);
            if (changed.contains("orientation")) applyOrientation();
            String warn = null;
            if (prefs.needsRestart(changed)) {
                if (cam.isRecording()) warn = "camera settings will apply after the recording"; else cam.restart();
            } else if (changed.contains("zoom") || changed.contains("torch") || changed.contains("exposureCompensation")) cam.updateControls();
            if (changed.contains("streamFps") || changed.contains("streamWidth") || changed.contains("streamQuality"))
                cam.setStreaming(cam.isStreaming(), prefs.i("streamFps"), prefs.i("streamWidth"), prefs.num("streamQuality"));
            fire();
            return warn;
        }

        @Override public void stream(boolean on, int fps, int width, double quality) {
            if (fps > 0) { try { prefs.apply(new JSONObject().put("streamFps", fps)); } catch (JSONException ignored) { } }
            if (width > 0) { try { prefs.apply(new JSONObject().put("streamWidth", width)); } catch (JSONException ignored) { } }
            if (quality > 0) { try { prefs.apply(new JSONObject().put("streamQuality", quality)); } catch (JSONException ignored) { } }
            cam.setStreaming(on, prefs.i("streamFps"), prefs.i("streamWidth"), prefs.num("streamQuality"));
            fire();
        }

        @Override public void snap(double quality, final SnapCallback cb) {
            cam.takePhoto(quality, new SnapCallback() {
                public void done(byte[] jpeg, int w, int h, String error) {
                    if (error == null && jpeg != null) { try { lib.addPhoto(jpeg, w, h); } catch (IOException e) { error = "cannot store photo: " + e.getMessage(); } }
                    cb.done(jpeg, w, h, error);
                    fire();
                }
            });
        }

        @Override public String recStart(String label) {
            meta.begin(label);
            String e = cam.startRecording(label);
            if (e != null) meta.clear();
            fire();
            return e;
        }
        @Override public JSONObject mark(String text, double hostT) { return meta.mark(text, hostT, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos()); }
        @Override public JSONObject recMeta(long id) { LibraryStore.Item it = lib.find("video", id); return it == null ? null : RecMeta.read(it.file); }
        @Override public long monoNs() { return SystemClock.elapsedRealtimeNanos(); }
        @Override public long wallMs() { return System.currentTimeMillis(); }
        @Override public void recStop() { cam.stopRecording(); }

        @Override public JSONObject recStatus() {
            JSONObject o = new JSONObject();
            try {
                o.put("state", cam.isRecording() ? "recording" : "idle"); o.put("elapsed", cam.recElapsed()); o.put("size", cam.recBytes());
                JSONObject live = meta.live();            // the clocks at the first frame, as soon as there is one
                if (live != null) { o.put("start_wall_ms", live.getLong("start_wall_ms")); o.put("start_mono_ns", live.getLong("start_mono_ns")); }
                LibraryStore.Item l = lastRec;
                if (l != null && lib.find("video", l.id) != null) {
                    JSONObject last = new JSONObject().put("id", l.id).put("size", l.size).put("name", l.name);
                    JSONObject saved = RecMeta.read(l.file);
                    if (saved != null) last.put("start_wall_ms", saved.optLong("start_wall_ms")).put("duration_ms", saved.optLong("duration_ms"));
                    o.put("last", last);
                }
            } catch (JSONException e) { throw new RuntimeException(e); }
            return o;
        }

        @Override public LibraryStore.Item recItem(long id) { return lib.find("video", id); }
        @Override public void discardRec(long id) { lib.delete("video", id); }
        @Override public LibraryStore library() { return lib; }
    }
}
