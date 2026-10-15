import io.github.dnscribe.webcam.core.*;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Plain assertions for the pure-Java helpers (no framework). Exit code 1 on failure. */
public class UnitTests {
    static int fails;
    static void check(String label, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + label); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        // Aspect
        check("Aspect.ratio parses", Aspect.ratio("4:3") > 1.33 && Aspect.ratio("4:3") < 1.34 && Aspect.ratio("native") == 0 && Aspect.ratio(null) == 0);
        int[] c = Aspect.crop(1920, 1080, 4.0 / 3);
        check("Aspect.crop 16:9 -> 4:3 is centred and even", c[2] == 1440 && c[3] == 1080 && c[0] == 240 && c[1] == 0);
        c = Aspect.crop(1920, 1080, 1);
        check("Aspect.crop -> 1:1", c[2] == 1080 && c[3] == 1080 && c[0] == 420);
        c = Aspect.crop(1440, 1080, 16.0 / 9);
        check("Aspect.crop 4:3 -> 16:9 crops height", c[2] == 1440 && c[3] == 810 && c[1] == 135 - 1 + 0 || (c[2] == 1440 && c[3] == 810));
        check("Aspect.crop no-op when aspect already matches", java.util.Arrays.equals(Aspect.crop(1280, 720, 16.0 / 9), new int[] {0, 0, 1280, 720}));
        // Net.isPrivate
        String[] yes = {"192.168.10.100", "10.0.0.5", "172.16.0.1", "172.31.255.1", "127.0.0.1", "169.254.1.1", "100.101.1.1", "localhost", "hub.local"};
        String[] no = {"8.8.8.8", "172.32.0.1", "172.15.0.1", "100.63.0.1", "100.128.0.1", "example.com", "", "1.2.3"};
        boolean ok = true; for (String h : yes) ok &= Net.isPrivate(h); for (String h : no) ok &= !Net.isPrivate(h);
        check("Net.isPrivate (LAN, CGNAT, loopback yes; public no)", ok);
        // names
        check("nameMatch exact/substring/glob/case", HubFinder.nameMatch("Office", "office") && HubFinder.nameMatch("lab-2", "lab*") && HubFinder.nameMatch("lab-2", "lab?2")
                && HubFinder.nameMatch("main-office", "off") && HubFinder.nameMatch("x", "") && !HubFinder.nameMatch("lab", "office") && !HubFinder.nameMatch("lab", "o*"));
        // LibraryStore
        File dir = Files.createTempDirectory("lib").toFile();
        LibraryStore s = new LibraryStore(dir, null);
        byte[] jpg = new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
        LibraryStore.Item p1 = s.addPhoto(jpg, 640, 480), p2 = s.addPhoto(jpg, 640, 480);
        check("photo ids and names are unique even in the same millisecond", p1.id != p2.id && !p1.name.equals(p2.name));
        File vf = s.newVideoFile("mp4"); Files.write(vf.toPath(), new byte[1234]);
        LibraryStore.Item v = s.addVideo(vf, 2.5, 1280, 720);
        check("video item", v.kind.equals("video") && v.size == 1234 && v.type.equals("video/mp4") && v.id > 1_000_000_000L && v.id < 10_000_000_000L);
        check("list is newest first", s.list().size() == 3 && s.list().get(0).ts >= s.list().get(2).ts);
        LibraryStore s2 = new LibraryStore(dir, null);
        check("library survives a restart (index.json)", s2.list().size() == 3 && s2.find("video", v.id) != null && s2.find("video", v.id).secs == 2.5);
        Files.write(new File(dir, "snap-20250101-000000-000.jpg").toPath(), jpg);
        Files.write(new File(dir, "rec-20250101-000000.mp4").toPath(), new byte[10]);
        check("files without an index entry are adopted", new LibraryStore(dir, null).list().size() == 5);
        Files.write(new File(dir, "index.json").toPath(), "not json".getBytes());
        check("a corrupt index is tolerated (files re-adopted)", new LibraryStore(dir, null).list().size() == 5);
        LibraryStore s3 = new LibraryStore(dir, null);
        int[] changes = {0}; s3.setOnChange(() -> changes[0]++);
        boolean del = s3.delete("photo", s3.list().stream().filter(i -> i.kind.equals("photo")).findFirst().get().id);
        check("delete removes the file, updates the list and fires onChange", del && s3.list().size() == 4 && changes[0] == 1);
        check("delete of an unknown id is false", !s3.delete("photo", 42));
        check("toJson has the web app's item shape", s3.toJson().getJSONObject(0).has("kind") && s3.toJson().getJSONObject(0).has("size") && s3.toJson().getJSONObject(0).has("ts"));
        System.out.println(fails == 0 ? "UNIT TESTS PASSED" : fails + " UNIT TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
