package io.github.dnscribe.webcam;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import io.github.dnscribe.webcam.core.Device;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * The camera screen: the live view takes all the space left and the immediate controls (flip, snap, record, zoom, torch) sit around it.
 * Closing it does NOT stop the webcam: the foreground service keeps the camera and the hub connection alive.
 */
public class MainActivity extends Activity implements CameraService.UiListener, TextureView.SurfaceTextureListener {
    /** TextureView that fits itself inside its parent with a fixed aspect ratio. */
    static final class AspectTextureView extends TextureView {
        private int aw = 16, ah = 9;
        AspectTextureView(Context c) { super(c); }
        void setAspect(int w, int h) { if (w > 0 && h > 0 && (w != aw || h != ah)) { aw = w; ah = h; requestLayout(); } }
        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
            if (w * ah > h * aw) w = h * aw / ah; else h = w * ah / aw;
            setMeasuredDimension(w, h);
        }
    }

    private LinearLayout root, controls, zoomRow;
    private FrameLayout holder;
    private AspectTextureView tv;
    private TextView status, badge;
    private Button snap, rec, flip, lib, settings;
    private SeekBar zoom;
    private CheckBox torch;
    private SurfaceTexture texture;
    private Surface attached;
    private Size attachedSize;
    private boolean updatingUi;

    // ------------------------------------------------------------------ UI construction
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private Button button(String text, int sizeSp) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(sizeSp); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        GradientDrawable g = new GradientDrawable(); g.setColor(0xFF1B222B); g.setCornerRadius(dp(14));
        b.setBackground(g);
        return b;
    }

    private void build() {
        root = new LinearLayout(this); root.setBackgroundColor(Color.BLACK);
        holder = new FrameLayout(this);
        tv = new AspectTextureView(this); tv.setSurfaceTextureListener(this);
        holder.addView(tv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        // top bar
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(10), dp(8), dp(6), dp(8)); top.setBackgroundColor(0x88000000);
        status = new TextView(this); status.setTextColor(Color.WHITE); status.setTextSize(13);
        badge = new TextView(this); badge.setTextColor(Color.WHITE); badge.setTextSize(13); badge.setPadding(dp(8), 0, dp(8), 0);
        settings = button("⚙", 18); lib = button("▦", 18);
        top.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(badge);
        top.addView(lib, new LinearLayout.LayoutParams(dp(48), dp(40)));
        top.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(40)));
        holder.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER); controls.setBackgroundColor(0xFF0B0D10); controls.setPadding(dp(8), dp(8), dp(8), dp(8));
        zoomRow = new LinearLayout(this); zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoom = new SeekBar(this); zoom.setMax(100);
        torch = new CheckBox(this); torch.setText("torch"); torch.setTextColor(Color.WHITE);
        zoomRow.addView(zoom, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        zoomRow.addView(torch);
        flip = button("⟲", 22); rec = button("⏺", 22);
        snap = new Button(this); snap.setText("●"); snap.setTextSize(28); snap.setTextColor(Color.parseColor("#222222"));
        GradientDrawable round = new GradientDrawable(); round.setShape(GradientDrawable.OVAL); round.setColor(Color.WHITE); round.setStroke(dp(5), 0xFF9AA4AF);
        snap.setBackground(round);
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user && !updatingUi) setZoom(p); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        torch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton b, boolean on) { if (!updatingUi) put("torch", on); } });
        flip.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { CameraService s = CameraService.get(); if (s != null) put("facing", "user".equals(s.prefs().str("facing")) ? "environment" : "user"); } });
        snap.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { snapPhoto(); } });
        rec.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggleRec(); } });
        lib.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startActivity(new Intent(MainActivity.this, LibraryActivity.class)); } });
        settings.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); } });
        layoutFor(getResources().getConfiguration());
        setContentView(root);
    }

    /** Portrait: view on top, controls below. Landscape: controls in a column on the right. */
    private void layoutFor(Configuration c) {
        boolean land = c.orientation == Configuration.ORIENTATION_LANDSCAPE;
        root.removeAllViews(); controls.removeAllViews();
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        controls.setOrientation(land ? LinearLayout.VERTICAL : LinearLayout.VERTICAL);
        LinearLayout buttons = new LinearLayout(this); buttons.setGravity(Gravity.CENTER);
        buttons.setOrientation(land ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (View v : new View[] {flip, snap, rec}) { if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v); }
        int big = dp(land ? 76 : 84), small = dp(land ? 54 : 64);
        LinearLayout.LayoutParams lf = land ? new LinearLayout.LayoutParams(dp(76), small) : new LinearLayout.LayoutParams(0, small, 1);
        LinearLayout.LayoutParams lr = land ? new LinearLayout.LayoutParams(dp(76), small) : new LinearLayout.LayoutParams(0, small, 1);
        lf.setMargins(dp(4), dp(4), dp(4), dp(4)); lr.setMargins(dp(4), dp(4), dp(4), dp(4));
        buttons.addView(flip, lf);
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(big, big); ls.setMargins(dp(8), dp(4), dp(8), dp(4));
        buttons.addView(snap, ls);
        buttons.addView(rec, lr);
        if (zoomRow.getParent() != null) ((ViewGroup) zoomRow.getParent()).removeView(zoomRow);
        if (!land) controls.addView(zoomRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        controls.addView(buttons, new LinearLayout.LayoutParams(land ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (holder.getParent() != null) ((ViewGroup) holder.getParent()).removeView(holder);
        if (land) {
            // zoom row goes into the preview area, bottom, so the right column stays compact
            if (zoomRow.getParent() != null) ((ViewGroup) zoomRow.getParent()).removeView(zoomRow);
            FrameLayout.LayoutParams zp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            zoomRow.setBackgroundColor(0x88000000); holder.addView(zoomRow, zp);
            root.addView(holder, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            root.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            zoomRow.setBackgroundColor(Color.TRANSPARENT);
            root.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            root.addView(controls, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    // ------------------------------------------------------------------ lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        build();
        ensurePermissions();
    }


    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        layoutFor(c);
        refresh();
    }

    @Override protected void onStart() {
        super.onStart();
        CameraService.UI.add(this);
        applyRequestedOrientation();
        refresh();
    }

    @Override protected void onStop() {
        CameraService.UI.remove(this);
        detachPreview();                       // the service keeps running; only the on-screen preview goes away
        super.onStop();
    }

    // ------------------------------------------------------------------ permissions
    private void ensurePermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) need.add("android.permission.POST_NOTIFICATIONS");
        if (need.isEmpty()) startCamera(); else requestPermissions(need.toArray(new String[0]), 7);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else status.setText("Camera permission is required - grant it in the system settings");
    }

    private void startCamera() {
        Intent i = new Intent(this, CameraService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    // ------------------------------------------------------------------ actions
    private void put(String k, Object v) {
        CameraService s = CameraService.get();
        if (s == null) return;
        try { s.applySetting(new JSONObject().put(k, v)); } catch (Exception ignored) { }
    }

    private void setZoom(int progress) {
        CameraService s = CameraService.get();
        if (s == null) return;
        JSONObject z = s.camera().caps().optJSONObject("zoom");
        if (z == null) return;
        double v = z.optDouble("min", 1) + (z.optDouble("max", 1) - z.optDouble("min", 1)) * progress / 100.0;
        put("zoom", Math.round(v * 100) / 100.0);
    }

    private void snapPhoto() {
        CameraService s = CameraService.get();
        if (s == null) { Toast.makeText(this, "Camera service not running", Toast.LENGTH_SHORT).show(); return; }
        s.device().snap(-1, new Device.SnapCallback() {
            public void done(byte[] jpeg, final int w, final int h, final String err) {
                runOnUiThread(new Runnable() { public void run() { Toast.makeText(MainActivity.this, err == null ? "Saved photo " + w + "×" + h : err, Toast.LENGTH_SHORT).show(); } });
            }
        });
    }

    private void toggleRec() {
        CameraService s = CameraService.get();
        if (s == null) return;
        if (s.camera().isRecording()) s.device().recStop();
        else { String e = s.device().recStart(""); if (e != null) Toast.makeText(this, e, Toast.LENGTH_LONG).show(); }
    }

    // ------------------------------------------------------------------ service -> UI
    @Override public void onServiceChanged() { refresh(); }

    private void applyRequestedOrientation() {
        CameraService s = CameraService.get();
        String o = s == null ? "auto" : s.prefs().str("orientation");
        int want = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        switch (o) {
            case "portrait": want = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; break;
            case "landscape": want = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; break;
            case "portrait2": want = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT; break;
            case "landscape2": want = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE; break;
            default: break;
        }
        if (getRequestedOrientation() != want) setRequestedOrientation(want);
    }

    private void refresh() {
        CameraService s = CameraService.get();
        if (s == null) { status.setText("starting…"); return; }
        applyRequestedOrientation();
        String dot = s.status().equals("on") ? "● " : s.status().equals("wait") ? "◐ " : "○ ";
        JSONObject v = s.camera().video();
        status.setText(dot + s.statusText() + (v.optInt("w") > 0 ? "   " + v.optInt("w") + "×" + v.optInt("h") : "") + (s.lastError().isEmpty() ? "" : "\n" + s.lastError()));
        boolean recording = s.camera().isRecording();
        badge.setText(recording ? "● REC " + (int) s.camera().recElapsed() + "s" : "");
        badge.setBackgroundColor(recording ? Color.RED : Color.TRANSPARENT);
        rec.setText(recording ? "⏹" : "⏺");
        // zoom / torch
        JSONObject caps = s.camera().caps();
        updatingUi = true;
        JSONObject z = caps.optJSONObject("zoom");
        zoom.setEnabled(z != null);
        if (z != null && z.optDouble("max") > z.optDouble("min")) zoom.setProgress((int) Math.round(100 * (s.prefs().num("zoom") - z.optDouble("min")) / (z.optDouble("max") - z.optDouble("min"))));
        torch.setEnabled(caps.optBoolean("torch")); torch.setChecked(s.prefs().bool("torch"));
        updatingUi = false;
        updatePreviewAspect();
        tryAttachPreview();
    }

    // ------------------------------------------------------------------ preview surface
    private void updatePreviewAspect() {
        CameraService s = CameraService.get();
        Size ps = s == null ? null : s.camera().previewSize();
        if (ps == null) return;
        boolean portrait = getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
        // the camera delivers landscape buffers; the system rotates them to the display's natural orientation
        if (portrait) tv.setAspect(ps.getHeight(), ps.getWidth()); else tv.setAspect(ps.getWidth(), ps.getHeight());
    }

    private void tryAttachPreview() {
        CameraService s = CameraService.get();
        if (s == null || texture == null) return;
        Size ps = s.camera().previewSize();
        if (ps == null || (attached != null && ps.equals(attachedSize))) return;
        texture.setDefaultBufferSize(ps.getWidth(), ps.getHeight());      // resolution/aspect changed: new buffer size, new surface
        Surface old = attached;
        attached = new Surface(texture); attachedSize = ps;
        s.camera().setPreview(attached);     // the old surface is not released explicitly: the camera thread may still be switching away from it
        configureTransform(tv.getWidth(), tv.getHeight());
    }

    private void detachPreview() {
        CameraService s = CameraService.get();
        if (s != null) s.camera().setPreview(null);
        attached = null; attachedSize = null;
    }

    private void configureTransform(int vw, int vh) {
        CameraService s = CameraService.get();
        Size ps = s == null ? null : s.camera().previewSize();
        if (ps == null || vw == 0 || vh == 0) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix m = new Matrix();
        RectF view = new RectF(0, 0, vw, vh), buf = new RectF(0, 0, ps.getHeight(), ps.getWidth());
        float cx = view.centerX(), cy = view.centerY();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            buf.offset(cx - buf.centerX(), cy - buf.centerY());
            m.setRectToRect(view, buf, Matrix.ScaleToFit.FILL);
            float scale = Math.min((float) vh / ps.getHeight(), (float) vw / ps.getWidth());
            m.postScale(scale, scale, cx, cy);
            m.postRotate(90 * (rotation - 2), cx, cy);
        } else if (rotation == Surface.ROTATION_180) m.postRotate(180, cx, cy);
        tv.setTransform(m);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { texture = st; tryAttachPreview(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { configureTransform(w, h); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { detachPreview(); texture = null; return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
}
