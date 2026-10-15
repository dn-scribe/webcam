package io.github.dnscribe.webcam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import io.github.dnscribe.webcam.core.LibraryStore;
import java.io.ByteArrayOutputStream;
import java.io.File;

/** Thumbnails for the library (hub protocol `lib_thumb` + the in-app list). */
public class AndroidThumbs implements LibraryStore.Thumbs {
    @Override public byte[] jpeg(File f, String kind, int width) {
        try {
            Bitmap b;
            if ("photo".equals(kind)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                o.inJustDecodeBounds = false;
                o.inSampleSize = Math.max(1, o.outWidth / (width * 2));
                b = BitmapFactory.decodeFile(f.getPath(), o);
            } else {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try { r.setDataSource(f.getPath()); b = r.getFrameAtTime(0); } finally { try { r.release(); } catch (Exception ignored) { } }
            }
            if (b == null) return null;
            Bitmap s = b.getWidth() > width ? Bitmap.createScaledBitmap(b, width, Math.max(1, b.getHeight() * width / b.getWidth()), true) : b;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            s.compress(Bitmap.CompressFormat.JPEG, 60, o);
            return o.toByteArray();
        } catch (Throwable t) { return null; }
    }
}
