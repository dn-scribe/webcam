package io.github.dnscribe.webcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import io.github.dnscribe.webcam.core.LibraryStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Photos and clips stored on the phone. Tap one for: save to gallery, share, send to the hub (PC), delete. */
public class LibraryActivity extends Activity implements CameraService.UiListener {
    private ListView list;
    private TextView empty;
    private List<LibraryStore.Item> items;
    private final ExecutorService thumbPool = Executors.newFixedThreadPool(2);
    private final AndroidThumbs thumbMaker = new AndroidThumbs();

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private final BaseAdapter adapter = new BaseAdapter() {
        public int getCount() { return items == null ? 0 : items.size(); }
        public Object getItem(int i) { return items.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            if (convert == null) {
                row = new LinearLayout(LibraryActivity.this); row.setPadding(dp(10), dp(8), dp(10), dp(8)); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                ImageView iv = new ImageView(LibraryActivity.this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackgroundColor(0xFF1B222B); iv.setId(1);
                row.addView(iv, new LinearLayout.LayoutParams(dp(88), dp(66)));
                TextView tv = new TextView(LibraryActivity.this); tv.setTextColor(Color.WHITE); tv.setId(2); tv.setPadding(dp(12), 0, 0, 0);
                row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            } else row = (LinearLayout) convert;
            final LibraryStore.Item it = items.get(pos);
            final ImageView iv = (ImageView) row.findViewById(1);
            ((TextView) row.findViewById(2)).setText((it.kind.equals("video") ? "🎞  " : "📷  ") + it.name + "\n" + (it.w > 0 ? it.w + "×" + it.h + " · " : "") + size(it.size)
                    + (it.kind.equals("video") ? " · " + Math.round(it.secs) + " s" : "") + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(it.ts)));
            iv.setImageBitmap(null); iv.setTag(it.file.getPath());
            thumbPool.submit(new Runnable() { public void run() {
                byte[] j = thumbMaker.jpeg(it.file, it.kind, 160);
                final Bitmap b = j == null ? null : BitmapFactory.decodeByteArray(j, 0, j.length);
                runOnUiThread(new Runnable() { public void run() { if (it.file.getPath().equals(iv.getTag())) iv.setImageBitmap(b); } });
            } });
            return row;
        }
    };

    private static String size(long n) { return n > 1_000_000 ? String.format("%.1f MB", n / 1e6) : Math.max(1, n / 1000) + " KB"; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xFF0B0D10);
        TextView title = new TextView(this); title.setText("Photos and clips"); title.setTextColor(Color.WHITE); title.setTextSize(20); title.setPadding(dp(16), dp(14), dp(16), dp(6));
        list = new ListView(this); list.setAdapter(adapter); list.setDividerHeight(0);
        empty = new TextView(this); empty.setText("Nothing here yet — take a photo or record a clip."); empty.setTextColor(0xFF8A96A3); empty.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(title); root.addView(empty); root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) { actions(items.get(pos)); }
        });
    }

    @Override protected void onStart() { super.onStart(); CameraService.UI.add(this); onServiceChanged(); }
    @Override protected void onStop() { CameraService.UI.remove(this); super.onStop(); }

    @Override public void onServiceChanged() {
        CameraService s = CameraService.get();
        items = s == null ? null : s.library().list();
        empty.setVisibility(items == null || items.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void actions(final LibraryStore.Item it) {
        final String[] a = Build.VERSION.SDK_INT >= 29 ? new String[] {"Share", "Save to gallery", "Send to the PC (hub)", "Delete"} : new String[] {"Send to the PC (hub)", "Delete"};
        new AlertDialog.Builder(this).setTitle(it.name).setItems(a, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                String c = a[which];
                if (c.startsWith("Share")) share(it);
                else if (c.startsWith("Save")) { Uri u = saveToGallery(it); Toast.makeText(LibraryActivity.this, u != null ? "Saved to the gallery" : "Could not save", Toast.LENGTH_SHORT).show(); }
                else if (c.startsWith("Send")) {
                    CameraService s = CameraService.get();
                    if (s != null && s.hub().isConnected()) { s.hub().pushToHub(it); Toast.makeText(LibraryActivity.this, "Sending to the hub…", Toast.LENGTH_SHORT).show(); }
                    else Toast.makeText(LibraryActivity.this, "Not connected to a hub", Toast.LENGTH_SHORT).show();
                } else confirmDelete(it);
            }
        }).show();
    }

    private void confirmDelete(final LibraryStore.Item it) {
        new AlertDialog.Builder(this).setMessage("Delete " + it.name + "?").setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    CameraService s = CameraService.get(); if (s != null) s.library().delete(it.kind, it.id); } }).show();
    }

    /** Copies the file into the shared gallery (Android 10+ MediaStore, no storage permission needed). */
    private Uri saveToGallery(LibraryStore.Item it) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            boolean photo = it.kind.equals("photo");
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, it.name); v.put(MediaStore.MediaColumns.MIME_TYPE, it.type);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, (photo ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_MOVIES) + "/Webcam");
            ContentResolver cr = getContentResolver();
            Uri u = cr.insert(photo ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return null;
            try (InputStream in = new FileInputStream(it.file); OutputStream out = cr.openOutputStream(u)) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return u;
        } catch (Exception e) { return null; }
    }

    private void share(LibraryStore.Item it) {
        Uri u = saveToGallery(it);
        if (u == null) { Toast.makeText(this, "Could not prepare the file for sharing", Toast.LENGTH_SHORT).show(); return; }
        Intent i = new Intent(Intent.ACTION_SEND).setType(it.type).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share"));
    }
}
