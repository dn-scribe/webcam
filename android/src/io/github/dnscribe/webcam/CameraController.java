package io.github.dnscribe.webcam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;
import io.github.dnscribe.webcam.core.Aspect;
import io.github.dnscribe.webcam.core.Device;
import io.github.dnscribe.webcam.core.LibraryStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Camera2 capture without any UI: works from a foreground service while the screen is locked.
 * Outputs: optional preview surface (when the activity is visible), YUV frames for the hub's preview stream (and photos if JPEG
 * is unavailable), JPEG stills, and a MediaRecorder surface while recording. If the device cannot configure all of them at once the
 * session falls back step by step (drop preview, then JPEG, then YUV).
 * Everything runs on one camera thread.
 */
public final class CameraController {
    public interface Callback {
        void onChanged();
        void onError(String msg);
        void onFrame(byte[] jpeg);
        boolean canSendFrame();
        void onRecordingFinished(File f, double secs, int w, int h, String error);
        /** The recorder delivered its first frame: the wall clock and the monotonic clock at that moment. */
        void onRecordingStarted(long wallMs, long monoNs);
    }

    private static final class Waiter { final Device.SnapCallback cb; final double q; Waiter(Device.SnapCallback c, double q) { cb = c; this.q = q; } }

    private final Context ctx;
    private final Prefs prefs;
    private final LibraryStore lib;
    private final Callback cb;
    private final CameraManager mgr;
    private final HandlerThread thread = new HandlerThread("webcam-camera");
    private final Handler h;

    private CameraDevice dev;
    private CameraCaptureSession session;
    private CameraCharacteristics chars;
    private String camId;
    private boolean wantOpen, opening;
    private int gen, level;
    private volatile Surface previewSurface;
    private volatile Size previewSize, vidSize, stillSize, yuvSize;
    private boolean stillCrop, inPreview, inYuv, inJpeg;
    private ImageReader yuvReader, jpegReader;
    private final ArrayDeque<Waiter> stillWaiters = new ArrayDeque<>();
    private Waiter yuvSnap;

    private MediaRecorder recorder;
    private Surface recSurface;
    private File recFile;
    private volatile boolean recording;
    private boolean recStarted;
    private volatile long recT0;
    private int recW, recH;

    private volatile boolean streaming;
    private volatile int streamFps = 10, streamW = 640;
    private volatile double streamQ = 0.6;
    private long lastFrameT;
    private volatile int rot;                 // device rotation, degrees clockwise from natural (OrientationEventListener convention)

    public CameraController(Context c, Prefs p, LibraryStore lib, Callback cb) {
        this.ctx = c.getApplicationContext(); this.prefs = p; this.lib = lib; this.cb = cb;
        mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        thread.start();
        h = new Handler(thread.getLooper());
    }

    // ------------------------------------------------------------------ public control
    public void start() { h.post(new Runnable() { public void run() { wantOpen = true; open(); } }); }
    public void stop() { h.post(new Runnable() { public void run() { wantOpen = false; abortRecording(); closeAll(); } }); }
    public void restart() { h.post(new Runnable() { public void run() { if (!wantOpen) return; abortRecording(); closeAll(); open(); } }); }
    public void updateControls() { h.post(new Runnable() { public void run() { if (session != null) startRepeating(); cb.onChanged(); } }); }
    public void release() { stop(); h.post(new Runnable() { public void run() { thread.quitSafely(); } }); }
    public void setRotation(int r) { rot = ((r % 360) + 360) % 360; }
    public int rotation() { return rot; }

    public void setStreaming(boolean on, int fps, int width, double q) {
        if (fps > 0) streamFps = fps;
        if (width > 0) streamW = width;
        if (q > 0) streamQ = Math.min(1, q);
        boolean was = streaming;
        streaming = on;
        if (width > 0) h.post(new Runnable() { public void run() {
            if (dev == null || !wantOpen || recording || chars == null) return;
            Size old = yuvSize; computeSizes();
            if (old != null && !old.equals(yuvSize)) createSession();       // only rebuild when the frame size really changed
        } });
    }
    public boolean isStreaming() { return streaming; }

    public void setPreview(final Surface s) {
        h.post(new Runnable() { public void run() { previewSurface = s; if (dev != null) { level = 0; createSession(); } } });
    }
    public Size previewSize() { return previewSize; }
    public boolean isRecording() { return recording; }
    public double recElapsed() { return recording && recStarted ? (SystemClock.elapsedRealtime() - recT0) / 1000.0 : 0; }
    public long recBytes() { File f = recFile; return recording && f != null ? f.length() : 0; }

    // ------------------------------------------------------------------ reporting
    public JSONObject caps() {
        JSONObject o = new JSONObject();
        CameraCharacteristics c = chars;
        if (c == null) return o;
        try {
            double zmin = 1, zmax = 1;
            if (Build.VERSION.SDK_INT >= 30) {
                Range<Float> zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zr != null) { zmin = zr.getLower(); zmax = zr.getUpper(); }
            } else {
                Float mz = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                if (mz != null) zmax = mz;
            }
            if (zmax > zmin) o.put("zoom", new JSONObject().put("min", zmin).put("max", zmax).put("step", 0.1));
            Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash != null && flash) o.put("torch", true);
            Range<Integer> er = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Rational step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (er != null && step != null && er.getUpper() > er.getLower()) {
                double s = step.doubleValue();
                o.put("exposureCompensation", new JSONObject().put("min", er.getLower() * s).put("max", er.getUpper() * s).put("step", s));
            }
        } catch (Exception ignored) { }
        return o;
    }

    public JSONArray devices() {
        JSONArray a = new JSONArray();
        try {
            for (String id : mgr.getCameraIdList()) {
                CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                String label = (f != null && f == CameraCharacteristics.LENS_FACING_FRONT ? "Front" : f != null && f == CameraCharacteristics.LENS_FACING_BACK ? "Back" : "External") + " (" + id + ")";
                a.put(new JSONObject().put("id", id).put("label", label));
            }
        } catch (Exception ignored) { }
        return a;
    }

    /** Output frame size (after rotation) and frame rate. */
    public JSONObject video() {
        JSONObject o = new JSONObject();
        Size s = vidSize;
        try {
            if (s != null) { boolean sw = chars != null && needRotation() % 180 != 0; o.put("w", sw ? s.getHeight() : s.getWidth()); o.put("h", sw ? s.getWidth() : s.getHeight()); }
            o.put("fps", prefs.i("fps"));
        } catch (Exception ignored) { }
        return o;
    }

    // ------------------------------------------------------------------ open / close
    private String chooseCamera() throws CameraAccessException {
        String[] ids = mgr.getCameraIdList();
        if (ids.length == 0) return null;
        String want = prefs.str("deviceId");
        for (String id : ids) if (id.equals(want)) return id;
        int facing = "user".equals(prefs.str("facing")) ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : ids) {
            Integer f = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f != null && f == facing) return id;
        }
        return ids[0];
    }

    private void open() {
        if (!wantOpen || dev != null || opening) return;
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { cb.onError("camera permission missing"); return; }
        try {
            String id = chooseCamera();
            if (id == null) { cb.onError("no camera found"); return; }
            camId = id; chars = mgr.getCameraCharacteristics(id);
            computeSizes();
            opening = true;
            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    opening = false;
                    if (!wantOpen) { d.close(); return; }
                    dev = d; level = 0; createSession(); cb.onChanged();
                }
                @Override public void onDisconnected(CameraDevice d) { opening = false; d.close(); if (dev == d) dev = null; closeAll(); cb.onError("camera disconnected"); reopenLater(); }
                @Override public void onError(CameraDevice d, int e) { opening = false; d.close(); if (dev == d) dev = null; closeAll(); cb.onError("camera error " + e); reopenLater(); }
            }, h);
        } catch (Exception e) {
            opening = false;
            cb.onError("cannot open camera: " + e.getMessage());
            reopenLater();
        }
    }

    private void reopenLater() { if (wantOpen) h.postDelayed(new Runnable() { public void run() { open(); } }, 3000); }

    private void closeSession() { if (session != null) { try { session.close(); } catch (Exception ignored) { } session = null; } }

    private void closeReaders() {
        if (yuvReader != null) { yuvReader.close(); yuvReader = null; }
        if (jpegReader != null) { jpegReader.close(); jpegReader = null; }
        inYuv = inJpeg = inPreview = false;
    }

    private void closeAll() {
        gen++;
        closeSession();
        closeReaders();
        if (dev != null) { try { dev.close(); } catch (Exception ignored) { } dev = null; }
        opening = false;
        while (!stillWaiters.isEmpty()) stillWaiters.poll().cb.done(null, 0, 0, "camera closed");
        if (yuvSnap != null) { yuvSnap.cb.done(null, 0, 0, "camera closed"); yuvSnap = null; }
    }

    // ------------------------------------------------------------------ sizes
    private static double ar(Size s) { return (double) Math.max(s.getWidth(), s.getHeight()) / Math.min(s.getWidth(), s.getHeight()); }
    private static int longEdge(Size s) { return Math.max(s.getWidth(), s.getHeight()); }

    private static Size pickNear(Size[] sizes, int longTarget, double wantAr) {
        Size best = null; double bestScore = Double.MAX_VALUE;
        if (sizes == null) return null;
        for (Size s : sizes) {
            double penalty = wantAr > 0 && Math.abs(ar(s) - wantAr) / wantAr > 0.02 ? 1e9 : 0;
            double score = penalty + (longTarget == Integer.MAX_VALUE ? -longEdge(s) : Math.abs(longEdge(s) - longTarget));
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    private void computeSizes() {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return;
        String res = prefs.str("res");
        boolean max = res.equals("max");
        int tw = 1280, th = 720;
        if (!max) { try { String[] p = res.split("x"); tw = Integer.parseInt(p[0]); th = Integer.parseInt(p[1]); } catch (Exception ignored) { } }
        double ratio = Aspect.ratio(prefs.str("aspect"));
        double wantAr = ratio > 0 ? ratio : (max ? 0 : (double) Math.max(tw, th) / Math.min(tw, th));
        Size[] vs = map.getOutputSizes(MediaRecorder.class);
        if (vs == null || vs.length == 0) vs = map.getOutputSizes(android.graphics.SurfaceTexture.class);
        Size v = pickNear(vs, max ? Integer.MAX_VALUE : Math.max(tw, th), wantAr);
        vidSize = v;
        double vAr = v == null ? 16.0 / 9 : ar(v), outAr = ratio > 0 ? ratio : vAr;

        // stills: largest JPEG of the output aspect, within the "photo size" setting
        Size[] js = map.getOutputSizes(ImageFormat.JPEG);
        Size still = null; stillCrop = false;
        String pr = prefs.str("photoRes");
        double maxPx = pr.endsWith("mp") ? Double.parseDouble(pr.substring(0, pr.length() - 2)) * 1e6 : Double.MAX_VALUE;
        if (js != null && js.length > 0) {
            if (pr.equals("video") && v != null) {
                still = pickNear(js, longEdge(v), outAr);
            } else {
                Size bestFit = null, smallest = null, largest = null;
                for (Size s : js) {
                    boolean match = Math.abs(ar(s) - outAr) / outAr <= 0.02;
                    if (largest == null || (long) s.getWidth() * s.getHeight() > (long) largest.getWidth() * largest.getHeight()) largest = s;
                    if (!match) continue;
                    long px = (long) s.getWidth() * s.getHeight();
                    if (smallest == null || px < (long) smallest.getWidth() * smallest.getHeight()) smallest = s;
                    if (px <= maxPx && (bestFit == null || px > (long) bestFit.getWidth() * bestFit.getHeight())) bestFit = s;
                }
                still = bestFit != null ? bestFit : smallest;
                if (still == null) { still = largest; }
            }
            if (still != null && Math.abs(ar(still) - outAr) / outAr > 0.02) stillCrop = true;
        }
        stillSize = still;

        // preview-stream frames: smallest YUV at least streamW wide with the video's aspect
        Size[] ys = map.getOutputSizes(ImageFormat.YUV_420_888);
        Size yuv = null;
        if (ys != null) {
            Size small = null, big = null;
            for (Size s : ys) {
                if (Math.abs(ar(s) - vAr) / vAr > 0.02) continue;
                if (longEdge(s) >= streamW && (small == null || longEdge(s) < longEdge(small))) small = s;
                if (big == null || longEdge(s) > longEdge(big)) big = s;
            }
            yuv = small != null ? small : big != null ? big : pickNear(ys, streamW, 0);
        }
        yuvSize = yuv;

        // on-screen preview: matches the video aspect, at most 1080p
        Size[] ps = map.getOutputSizes(android.graphics.SurfaceTexture.class);
        previewSize = pickNear(ps, 1920, vAr);
    }

    // ------------------------------------------------------------------ session
    private void createSession() {
        if (dev == null || yuvSize == null) return;
        closeSession(); closeReaders();
        final int g = ++gen;
        List<Surface> out = new ArrayList<>();
        Surface prev = previewSurface;
        boolean usePrev = prev != null && prev.isValid() && level < 1;
        boolean useYuv = level < 3 && yuvSize != null;
        boolean useJpeg = level < 2 && stillSize != null;
        try {
            if (useYuv) {
                yuvReader = ImageReader.newInstance(yuvSize.getWidth(), yuvSize.getHeight(), ImageFormat.YUV_420_888, 2);
                yuvReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() { public void onImageAvailable(ImageReader r) { onYuv(r); } }, h);
                out.add(yuvReader.getSurface()); inYuv = true;
            }
            if (useJpeg || (level >= 3 && !recording && stillSize != null)) {
                jpegReader = ImageReader.newInstance(stillSize.getWidth(), stillSize.getHeight(), ImageFormat.JPEG, 2);
                jpegReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() { public void onImageAvailable(ImageReader r) { onJpeg(r); } }, h);
                out.add(jpegReader.getSurface()); inJpeg = true;
            }
            if (usePrev) { out.add(prev); inPreview = true; }
            if (recording && recSurface != null) out.add(recSurface);
            if (out.isEmpty()) { cb.onError("no usable camera outputs"); return; }
            dev.createCaptureSession(out, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    if (g != gen || dev == null) { s.close(); return; }
                    session = s;
                    startRepeating();
                    if (recording && !recStarted && recorder != null) {
                        try { long wall = System.currentTimeMillis(), mono = SystemClock.elapsedRealtimeNanos(); recorder.start(); recStarted = true; recT0 = SystemClock.elapsedRealtime(); cb.onRecordingStarted(wall, mono); }
                        catch (Exception e) { failRecording("cannot start recorder: " + e.getMessage()); }
                    }
                    cb.onChanged();
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    if (g != gen) return;
                    if (level < 3) { level++; createSession(); }
                    else { cb.onError("camera session could not be configured"); failRecording("camera session could not be configured"); }
                }
            }, h);
        } catch (Exception e) {
            cb.onError("camera session: " + e.getMessage());
        }
    }

    private void applyControls(CaptureRequest.Builder b, boolean video) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        // focus
        int[] af = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        int mode = CaptureRequest.CONTROL_AF_MODE_AUTO;
        boolean cv = false, cp = false;
        if (af != null) for (int m : af) { if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) cv = true; if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) cp = true; }
        if (video && cv) mode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO; else if (cp) mode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE; else if (cv) mode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
        if (af != null && af.length > 0) b.set(CaptureRequest.CONTROL_AF_MODE, mode);
        // frame rate
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        int want = prefs.i("fps");
        if (ranges != null) {
            Range<Integer> best = null; int bestScore = Integer.MAX_VALUE;
            for (Range<Integer> r : ranges) {
                int score = Math.abs(r.getUpper() - want) * 4 + Math.abs(r.getLower() - want) + (r.getUpper() < want ? 100 : 0);
                if (score < bestScore) { bestScore = score; best = r; }
            }
            if (best != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best);
        }
        // exposure compensation
        Range<Integer> er = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        Rational step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (er != null && step != null && step.doubleValue() > 0) {
            int idx = (int) Math.round(prefs.num("exposureCompensation") / step.doubleValue());
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, Math.max(er.getLower(), Math.min(er.getUpper(), idx)));
        }
        // torch
        Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        if (flash != null && flash) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.FLASH_MODE, prefs.bool("torch") ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        }
        // zoom
        float z = (float) prefs.num("zoom");
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> zr = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zr != null) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.max(zr.getLower(), Math.min(zr.getUpper(), z)));
        } else {
            Rect a = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float mz = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            if (a != null && mz != null) {
                float zz = Math.max(1, Math.min(mz, z));
                int cw = (int) (a.width() / zz), ch = (int) (a.height() / zz);
                b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(a.centerX() - cw / 2, a.centerY() - ch / 2, a.centerX() + cw / 2, a.centerY() + ch / 2));
            }
        }
    }

    private void startRepeating() {
        if (dev == null || session == null) return;
        try {
            CaptureRequest.Builder b = dev.createCaptureRequest(recording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            if (inPreview && previewSurface != null) b.addTarget(previewSurface);
            if (inYuv && yuvReader != null) b.addTarget(yuvReader.getSurface());
            if (recording && recSurface != null) b.addTarget(recSurface);
            applyControls(b, recording);
            session.setRepeatingRequest(b.build(), null, h);
        } catch (Exception e) { cb.onError("repeating request: " + e.getMessage()); }
    }

    // ------------------------------------------------------------------ orientation
    /** Clockwise rotation needed to make a sensor image upright (the Camera2 JPEG_ORIENTATION / MediaRecorder hint convention). */
    private int needRotation() {
        Integer so = chars == null ? null : chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Integer f = chars == null ? null : chars.get(CameraCharacteristics.LENS_FACING);
        int s = so == null ? 90 : so, r = rot;
        boolean front = f != null && f == CameraCharacteristics.LENS_FACING_FRONT;
        return front ? (s - r + 360) % 360 : (s + r) % 360;
    }

    // ------------------------------------------------------------------ preview-stream frames / YUV photos
    private void onYuv(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            Waiter snap = yuvSnap;
            long now = SystemClock.elapsedRealtime();
            boolean wantStream = streaming && now - lastFrameT >= 1000 / Math.max(1, streamFps) && cb.canSendFrame();
            if (snap == null && !wantStream) return;
            int w = img.getWidth(), hh = img.getHeight();
            byte[] nv21 = toNv21(img);
            double ratio = Aspect.ratio(prefs.str("aspect"));
            if (snap != null) {
                yuvSnap = null;
                byte[] j = yuvToJpeg(nv21, w, hh, ratio, 0, snap.q > 0 ? snap.q : prefs.num("snapQuality"));
                int need = needRotation();
                int[] c = Aspect.crop(w, hh, ratio);
                snap.cb.done(j, need % 180 == 0 ? c[2] : c[3], need % 180 == 0 ? c[3] : c[2], j == null ? "encode failed" : null);
            }
            if (wantStream) {
                lastFrameT = now;
                byte[] j = yuvToJpeg(nv21, w, hh, ratio, streamW, streamQ);
                if (j != null) cb.onFrame(j);
            }
        } catch (Throwable t) {
            cb.onError("frame: " + t);
        } finally { if (img != null) img.close(); }
    }

    private static byte[] toNv21(Image img) {
        int w = img.getWidth(), hh = img.getHeight();
        Image.Plane[] p = img.getPlanes();
        byte[] out = new byte[w * hh * 3 / 2];
        ByteBuffer yb = p[0].getBuffer();
        int ys = p[0].getRowStride(), pos = 0;
        for (int row = 0; row < hh; row++) { yb.position(row * ys); yb.get(out, pos, w); pos += w; }
        ByteBuffer ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int us = p[1].getRowStride(), up = p[1].getPixelStride(), vs = p[2].getRowStride(), vp = p[2].getPixelStride();
        for (int row = 0; row < hh / 2; row++) {
            for (int col = 0; col < w / 2; col++) {
                out[pos++] = vb.get(row * vs + col * vp);
                out[pos++] = ub.get(row * us + col * up);
            }
        }
        return out;
    }

    /** NV21 -> JPEG, centre-cropped to the output aspect, rotated upright, optionally scaled down to outW. */
    private byte[] yuvToJpeg(byte[] nv21, int w, int hh, double ratio, int outW, double q) {
        try {
            int[] c = Aspect.crop(w, hh, ratio);
            int need = needRotation();
            int rw = need % 180 == 0 ? c[2] : c[3];
            boolean post = need != 0 || (outW > 0 && rw > outW);
            YuvImage yi = new YuvImage(nv21, ImageFormat.NV21, w, hh, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (!yi.compressToJpeg(new Rect(c[0], c[1], c[0] + c[2], c[1] + c[3]), post ? 92 : (int) Math.round(Math.max(0.1, q) * 100), bos)) return null;
            if (!post) return bos.toByteArray();
            byte[] raw = bos.toByteArray();
            Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (b == null) return null;
            Matrix m = new Matrix();
            m.postRotate(need);
            if (outW > 0 && rw > outW) { float s = (float) outW / rw; m.postScale(s, s); }
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            r.compress(Bitmap.CompressFormat.JPEG, (int) Math.round(Math.max(0.1, q) * 100), o);
            if (r != b) r.recycle();
            b.recycle();
            return o.toByteArray();
        } catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------ photos
    public void takePhoto(final double quality, final Device.SnapCallback done) {
        h.post(new Runnable() { public void run() {
            if (dev == null || session == null) { done.done(null, 0, 0, "camera not ready"); return; }
            double q = quality > 0 ? quality : prefs.num("snapQuality");
            if (!inJpeg || jpegReader == null) {
                if (inYuv) { if (yuvSnap != null) { done.done(null, 0, 0, "busy"); return; } yuvSnap = new Waiter(done, q); return; }
                done.done(null, 0, 0, "photos unavailable right now"); return;
            }
            try {
                CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(jpegReader.getSurface());
                applyControls(b, false);
                b.set(CaptureRequest.JPEG_ORIENTATION, stillCrop ? 0 : needRotation());
                b.set(CaptureRequest.JPEG_QUALITY, (byte) Math.max(1, Math.min(100, Math.round(q * 100))));
                stillWaiters.add(new Waiter(done, q));
                session.capture(b.build(), null, h);
            } catch (Exception e) { stillWaiters.pollLast(); done.done(null, 0, 0, "capture failed: " + e.getMessage()); }
        } });
    }

    private void onJpeg(ImageReader r) {
        Image img = null;
        byte[] bytes;
        try {
            img = r.acquireNextImage();
            if (img == null) return;
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            bytes = new byte[buf.remaining()];
            buf.get(bytes);
        } catch (Throwable t) { return; } finally { if (img != null) img.close(); }
        Waiter w = stillWaiters.poll();
        if (w == null) return;
        try {
            int need = needRotation();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sw = o.outWidth, sh = o.outHeight;
            if (!stillCrop) { w.cb.done(bytes, need % 180 == 0 ? sw : sh, need % 180 == 0 ? sh : sw, null); return; }
            double ratio = Aspect.ratio(prefs.str("aspect"));
            double outAr = ratio > 0 ? ratio : (vidSize == null ? 16.0 / 9 : ar(vidSize));
            int[] c = Aspect.crop(sw, sh, outAr);
            BitmapRegionDecoder d = BitmapRegionDecoder.newInstance(bytes, 0, bytes.length, false);
            Bitmap region = d.decodeRegion(new Rect(c[0], c[1], c[0] + c[2], c[1] + c[3]), new BitmapFactory.Options());
            d.recycle();
            Matrix m = new Matrix(); m.postRotate(need);
            Bitmap rb = Bitmap.createBitmap(region, 0, 0, region.getWidth(), region.getHeight(), m, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            rb.compress(Bitmap.CompressFormat.JPEG, (int) Math.round(Math.max(0.5, w.q) * 100), bos);
            int ow = rb.getWidth(), oh = rb.getHeight();
            if (rb != region) rb.recycle();
            region.recycle();
            w.cb.done(bos.toByteArray(), ow, oh, null);
        } catch (Throwable t) { w.cb.done(null, 0, 0, "photo processing failed: " + t); }
    }

    // ------------------------------------------------------------------ recording
    private int bitrate() {
        switch (prefs.str("bitrate")) { case "low": return 1_000_000; case "high": return 5_000_000; case "max": return 10_000_000; default: return 2_500_000; }
    }

    /** Returns an error message or null (recording starts asynchronously once the new session is configured). */
    public String startRecording(final String label) {
        FutureTask<String> t = new FutureTask<>(new java.util.concurrent.Callable<String>() { public String call() { return doStartRecording(label); } });
        h.post(t);
        try { return t.get(8, TimeUnit.SECONDS); } catch (Exception e) { return "recorder busy: " + e; }
    }

    private String doStartRecording(String label) {
        if (recording) return "already recording";
        if (dev == null || vidSize == null) return "camera not ready";
        MediaRecorder r = new MediaRecorder();
        try {
            recFile = lib.newVideoFile("mp4", label);
            boolean audio = prefs.bool("audio") && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setOutputFile(recFile.getAbsolutePath());
            r.setVideoEncodingBitRate(bitrate());
            r.setVideoFrameRate(Math.max(1, prefs.i("fps")));
            r.setVideoSize(vidSize.getWidth(), vidSize.getHeight());
            r.setVideoEncoder("hevc".equals(prefs.str("codec")) ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
            if (audio) { r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setAudioEncodingBitRate(96000); r.setAudioSamplingRate(44100); }
            r.setOrientationHint(needRotation());
            r.prepare();
        } catch (Exception e) {
            try { r.release(); } catch (Exception ignored) { }
            if (recFile != null) recFile.delete();
            return "cannot start recording: " + e.getMessage();
        }
        recorder = r; recSurface = r.getSurface(); recording = true; recStarted = false;
        int need = needRotation();
        recW = need % 180 == 0 ? vidSize.getWidth() : vidSize.getHeight(); recH = need % 180 == 0 ? vidSize.getHeight() : vidSize.getWidth();
        level = 0;
        createSession();
        return null;
    }

    public void stopRecording() { h.post(new Runnable() { public void run() { finishRecording(null); } }); }

    private void failRecording(String msg) { finishRecording(msg); }

    private void abortRecording() { if (recording) finishRecording("recording aborted"); }

    private void finishRecording(String error) {
        if (!recording) return;
        recording = false;
        double secs = recStarted ? (SystemClock.elapsedRealtime() - recT0) / 1000.0 : 0;
        MediaRecorder r = recorder; File f = recFile; boolean started = recStarted;
        recorder = null; recSurface = null; recStarted = false;
        try { if (session != null) { session.stopRepeating(); session.abortCaptures(); } } catch (Exception ignored) { }
        String err = error;
        try { if (started) r.stop(); else if (err == null) err = "recording did not start"; }
        catch (RuntimeException e) { if (err == null) err = "recording too short"; }
        try { r.reset(); r.release(); } catch (Exception ignored) { }
        if (err != null && f != null) f.delete();
        level = 0;
        if (dev != null) createSession();
        cb.onRecordingFinished(err == null ? f : null, secs, recW, recH, err);
        cb.onChanged();
    }
}
