package io.github.dnscribe.webcam.core;

/** Output-frame geometry shared by preview, photos, clips and the preview stream (WYSIWYG). */
public final class Aspect {
    private Aspect() {}

    /** "16:9" etc. -> landscape ratio; "native"/unknown -> 0. */
    public static double ratio(String s) {
        if (s == null) return 0;
        switch (s) {
            case "16:9": return 16.0 / 9;
            case "4:3": return 4.0 / 3;
            case "3:2": return 3.0 / 2;
            case "1:1": return 1;
            case "21:9": return 21.0 / 9;
            default: return 0;
        }
    }

    /** Largest centred rectangle of aspect `ar` (w/h) inside w x h -> {x, y, w, h}. */
    public static int[] crop(int w, int h, double ar) {
        double cur = (double) w / h;
        if (ar <= 0 || Math.abs(cur - ar) / ar < 0.01) return new int[] {0, 0, w, h};
        if (cur > ar) { int cw = (int) Math.round(h * ar); cw &= ~1; return new int[] {((w - cw) / 2) & ~1, 0, cw, h}; }
        int ch = (int) Math.round(w / ar); ch &= ~1;
        return new int[] {0, ((h - ch) / 2) & ~1, w, ch};
    }
}
