import io.github.dnscribe.webcam.core.*;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import javax.imageio.ImageIO;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Protocol test harness: a fake phone (no camera) that runs the real HubSession + LibraryStore against a real hub.
 * usage: Harness run  host port tls(true|false) cameraName token dir
 *        Harness probe host port tls
 * stdin commands: push | quit
 */
public class Harness {
    static byte[] jpeg(int w, int h, int seed) throws IOException {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(new Color(40 + seed * 40 % 200, 120, 200 - seed * 30 % 150)); g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE); g.drawString("frame " + seed, 10, 20); g.dispose();
        ByteArrayOutputStream o = new ByteArrayOutputStream(); ImageIO.write(im, "jpg", o); return o.toByteArray();
    }

    static class Fake implements Device {
        final LibraryStore lib; JSONObject settings = new JSONObject(); volatile boolean streaming; volatile int sfps = 10;
        volatile boolean recording; long recStart; File recFile; LibraryStore.Item last; HubSession session; int seed;
        Fake(LibraryStore lib) throws Exception {
            this.lib = lib;
            settings.put("facing", "environment"); settings.put("res", "1280x720"); settings.put("aspect", "native"); settings.put("fps", 30);
            settings.put("zoom", 1.0); settings.put("torch", false); settings.put("bitrate", "medium"); settings.put("photoRes", "max");
        }
        public JSONObject state() {
            try {
                JSONObject s = new JSONObject();
                s.put("version", "test"); s.put("build", "harness"); s.put("settings", settings);
                s.put("caps", new JSONObject("{\"zoom\":{\"min\":1,\"max\":8,\"step\":0.1},\"torch\":true}"));
                s.put("devices", new JSONArray("[{\"id\":\"0\",\"label\":\"Fake back\"}]"));
                s.put("video", new JSONObject("{\"w\":1280,\"h\":720,\"fps\":30}"));
                s.put("recording", recStatus()); s.put("streaming", streaming);
                return s;
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        public String set(JSONObject st) {
            try { for (Iterator<String> it = st.keys(); it.hasNext();) { String k = it.next(); settings.put(k, st.get(k)); } } catch (Exception e) { return e.getMessage(); }
            if (st.has("streamFps")) sfps = st.optInt("streamFps");
            return st.has("bogus") ? "unsupported setting: bogus" : null;
        }
        public void stream(boolean on, int fps, int width, double q) {
            if (fps > 0) sfps = fps; streaming = on;
            if (on) new Thread(() -> { int n = 0; while (streaming) { try { session.sendFrame(jpeg(320, 180, n++)); Thread.sleep(1000 / Math.max(1, sfps)); } catch (Exception e) { return; } } }).start();
        }
        public void snap(double q, SnapCallback cb) {
            try { byte[] j = jpeg(640, 360, ++seed); lib.addPhoto(j, 640, 360); cb.done(j, 640, 360, null); session.notifyLibrary(false); }
            catch (Exception e) { cb.done(null, 0, 0, e.toString()); }
        }
        public String recStart() { if (recording) return "already recording"; recording = true; recStart = System.currentTimeMillis(); recFile = lib.newVideoFile("mp4"); return null; }
        public void recStop() {
            if (!recording) return;
            try { byte[] b = new byte[700_000]; new Random(7).nextBytes(b); Files.write(recFile.toPath(), b); } catch (Exception e) { throw new RuntimeException(e); }
            recording = false; last = lib.addVideo(recFile, (System.currentTimeMillis() - recStart) / 1000.0, 1280, 720);
            session.sendRecStatus(); session.notifyLibrary(false);
        }
        public JSONObject recStatus() {
            try {
                JSONObject o = new JSONObject(); o.put("state", recording ? "recording" : "idle");
                o.put("elapsed", recording ? (System.currentTimeMillis() - recStart) / 1000.0 : 0); o.put("size", 0);
                if (last != null) o.put("last", new JSONObject().put("id", last.id).put("size", last.size).put("name", last.name));
                return o;
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        public LibraryStore.Item recItem(long id) { return lib.find("video", id); }
        public void discardRec(long id) { lib.delete("video", id); }
        public LibraryStore library() { return lib; }
    }

    public static void main(String[] a) throws Exception {
        if (a[0].equals("probe")) {
            HubFinder.Hub h = new HubFinder().probe(a[1], Integer.parseInt(a[2]), Boolean.parseBoolean(a[3]), 3000);
            System.out.println("PROBE " + (h == null ? "none" : h.name + "|" + h.tokenRequired + "|" + h.cameras));
            System.exit(0);
        }
        LibraryStore.Thumbs th = (f, kind, w) -> {
            try {
                if (!kind.equals("photo")) return null;
                BufferedImage s = ImageIO.read(f), d = new BufferedImage(w, Math.max(1, s.getHeight() * w / s.getWidth()), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = d.createGraphics(); g.drawImage(s, 0, 0, d.getWidth(), d.getHeight(), null); g.dispose();
                ByteArrayOutputStream o = new ByteArrayOutputStream(); ImageIO.write(d, "jpg", o); return o.toByteArray();
            } catch (Exception e) { return null; }
        };
        LibraryStore lib = new LibraryStore(new File(a[6]), th);
        final Fake dev = new Fake(lib);
        HubSession.Config c = new HubSession.Config();
        c.host = a[1]; c.port = Integer.parseInt(a[2]); c.tls = Boolean.parseBoolean(a[3]); c.name = a[4]; c.token = a[5]; c.version = "harness";
        final HubSession s = new HubSession(dev, new HubSession.Listener() {
            public void onStatus(String st, String text) { System.out.println("STATUS " + st + " " + text); System.out.flush(); }
            public void onWelcome(String cam, String hub) { System.out.println("WELCOME " + cam + " " + hub); System.out.flush(); }
        });
        dev.session = s;
        s.start(c);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals("quit")) break;
            if (line.equals("push")) { List<LibraryStore.Item> l = lib.list(); if (!l.isEmpty()) s.pushToHub(l.get(0)); System.out.println("PUSHING " + (l.isEmpty() ? "-" : l.get(0).name)); System.out.flush(); }
        }
        s.shutdown(); System.exit(0);
    }
}
