package ru.iqwanoino.picablur;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1, REQ_PERM = 2;

    private static final int BG = 0xFF0A0A0A, CARD = 0xFF1A1A1A, CHIP = 0xFF262626,
            LINE = 0xFF3A3A3A, WHITE = 0xFFFFFFFF, BLACK = 0xFF000000, MUTED = 0xFF9A9A9A;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final BlurEngine.Params params = new BlurEngine.Params();
    private final Runnable renderTask = new Runnable() {
        @Override public void run() { render(); }
    };
    private final ImageView[] thumbs = new ImageView[BlurEngine.LABELS.length];
    private final TextView[] labels = new TextView[BlurEngine.LABELS.length];

    private float dp;
    private Bitmap original, small;
    private String baseName = "image";
    private int gen;

    private PreviewView preview;
    private SliderRow sStrength, sAngle, sPusat;
    private LinearLayout spotRow;
    private Switch spotSwitch;

    private interface Listener {
        void onValue(int v);
    }

    /** Baris slider: label, nilai, tombol -, SeekBar, tombol +. */
    private class SliderRow {
        final LinearLayout root;
        final SeekBar bar;
        private final TextView value;
        private final String suffix;

        SliderRow(String label, int max, int initial, String suffix, Listener l) {
            this.suffix = suffix;
            root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(0, dp(10), 0, dp(10));

            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(text(label, 15, WHITE, false), new LinearLayout.LayoutParams(0, -2, 1f));
            value = text("", 15, WHITE, false);
            head.addView(value, new LinearLayout.LayoutParams(-2, -2));
            root.addView(head, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout ctrl = new LinearLayout(MainActivity.this);
            ctrl.setOrientation(LinearLayout.HORIZONTAL);
            ctrl.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams ctl = new LinearLayout.LayoutParams(-1, -2);
            ctl.topMargin = dp(8);
            root.addView(ctrl, ctl);

            TextView minus = circle("\u2212");
            TextView plus = circle("+");
            bar = new SeekBar(MainActivity.this);
            bar.setMax(max);
            bar.setProgress(initial);
            bar.setProgressTintList(ColorStateList.valueOf(WHITE));
            bar.setThumbTintList(ColorStateList.valueOf(WHITE));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));

            ctrl.addView(minus, new LinearLayout.LayoutParams(dp(34), dp(34)));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1f);
            bl.setMargins(dp(10), 0, dp(10), 0);
            ctrl.addView(bar, bl);
            ctrl.addView(plus, new LinearLayout.LayoutParams(dp(34), dp(34)));
            value.setText(initial + suffix);

            minus.setOnClickListener(v -> bar.setProgress(Math.max(0, bar.getProgress() - 1)));
            plus.setOnClickListener(v -> bar.setProgress(Math.min(max, bar.getProgress() + 1)));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    value.setText(p + suffix);
                    l.onValue(p);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) { }
                @Override public void onStopTrackingTouch(SeekBar sb) { }
            });
        }
    }

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        updateControls();
        updateChips();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(renderTask);
        exec.shutdownNow();
    }

    // ================================================================ UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(20), dp(16), dp(8));
        header.addView(text("Picablur", 22, WHITE, true), new LinearLayout.LayoutParams(0, -2, 1f));

        TextView imp = text("Import", 14, WHITE, false);
        imp.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_image, 0, 0, 0);
        imp.setCompoundDrawablePadding(dp(6));
        imp.setPadding(dp(14), dp(8), dp(16), dp(8));
        imp.setBackground(rr(BG, 20, WHITE, 1));
        imp.setOnClickListener(v -> pickImage());
        header.addView(imp, new LinearLayout.LayoutParams(-2, -2));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // Preview (utuh, tidak terpotong)
        preview = new PreviewView(this);
        preview.setCenterListener((x, y) -> {
            params.cx = x;
            params.cy = y;
            scheduleRender();
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, 0, 1f);
        pl.setMargins(dp(12), dp(4), dp(12), dp(4));
        root.addView(preview, pl);

        // Kartu kontrol
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rr(CARD, 20, 0, 0));
        card.setPadding(dp(16), dp(8), dp(16), dp(8));
        spotRow = new LinearLayout(this);
        spotRow.setOrientation(LinearLayout.HORIZONTAL);
        spotRow.setGravity(Gravity.CENTER_VERTICAL);
        spotRow.setPadding(0, dp(10), 0, dp(10));
        spotRow.addView(text("Spot pusat", 15, WHITE, false),
                new LinearLayout.LayoutParams(0, -2, 1f));
        spotSwitch = new Switch(this);
        spotSwitch.setChecked(false);
        spotSwitch.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{WHITE, 0xFF8A8A8A}));
        spotSwitch.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0xFF6A6A6A, 0xFF333333}));
        spotSwitch.setOnCheckedChangeListener((b, on) -> {
            params.spot = on;
            updateControls();
            scheduleRender();
        });
        spotRow.addView(spotSwitch, new LinearLayout.LayoutParams(-2, -2));
        card.addView(spotRow);

        sStrength = new SliderRow("Kekuatan", 100, 40, "", v -> {
            params.strength = v;
            scheduleRender();
        });
        sAngle = new SliderRow("Arah", 359, 0, "\u00B0", v -> {
            params.angle = v;
            scheduleRender();
        });
        sPusat = new SliderRow("Radius Pusat", 100, 0, "%", v -> {
            params.pusat = v;
            scheduleRender();
        });
        card.addView(sStrength.root);
        card.addView(sAngle.root);
        card.addView(sPusat.root);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.setMargins(dp(12), dp(4), dp(12), dp(4));
        root.addView(card, cl);

        // Pilihan jenis blur
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(dp(12), dp(4), dp(12), dp(4));
        for (int i = 0; i < BlurEngine.LABELS.length; i++) {
            final int idx = i;
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER_HORIZONTAL);

            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbs[i] = thumb;
            chip.addView(thumb, new LinearLayout.LayoutParams(dp(84), dp(84)));

            TextView lb = text(BlurEngine.LABELS[i], 11, MUTED, false);
            lb.setGravity(Gravity.CENTER);
            lb.setMaxLines(2);
            labels[i] = lb;
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(84), -2);
            ll.topMargin = dp(4);
            chip.addView(lb, ll);

            chip.setOnClickListener(v -> setStyle(idx));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
            chipLp.rightMargin = dp(10);
            strip.addView(chip, chipLp);
        }
        hs.addView(strip);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = dp(8);
        root.addView(hs, hl);

        // Tombol Download: putih, teks & ikon hitam
        LinearLayout dl = new LinearLayout(this);
        dl.setOrientation(LinearLayout.HORIZONTAL);
        dl.setGravity(Gravity.CENTER);
        dl.setBackground(rr(WHITE, 26, 0, 0));
        dl.setOnClickListener(v -> save());
        ImageView dlIcon = new ImageView(this);
        dlIcon.setImageResource(R.drawable.ic_download);
        dl.addView(dlIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView dlText = text("Download", 16, BLACK, true);
        LinearLayout.LayoutParams dtl = new LinearLayout.LayoutParams(-2, -2);
        dtl.leftMargin = dp(8);
        dl.addView(dlText, dtl);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(52));
        dlp.setMargins(dp(16), dp(12), dp(16), dp(20));
        root.addView(dl, dlp);

        return root;
    }

    private void updateControls() {
        boolean motion = params.style == BlurEngine.MOTION;
        boolean pointer = !motion && params.spot;
        spotRow.setVisibility(motion ? View.GONE : View.VISIBLE);
        sAngle.root.setVisibility(motion ? View.VISIBLE : View.GONE);
        sPusat.root.setVisibility(pointer ? View.VISIBLE : View.GONE);
        preview.setCenterVisible(pointer);
    }

    private void updateChips() {
        for (int i = 0; i < thumbs.length; i++) {
            boolean sel = params.style == i;
            thumbs[i].setBackground(rr(CHIP, 14, sel ? WHITE : LINE, sel ? 2 : 1));
            labels[i].setTextColor(sel ? WHITE : MUTED);
        }
    }

    private void setStyle(int style) {
        params.style = style;
        updateControls();
        updateChips();
        scheduleRender();
    }

    // ================================================================ render

    private void scheduleRender() {
        ui.removeCallbacks(renderTask);
        ui.postDelayed(renderTask, 40);
    }

    private void render() {
        if (small == null) return;
        final int g = ++gen;
        final BlurEngine.Params p = params.copy();
        final Bitmap src = small;
        exec.execute(() -> {
            final Bitmap out = BlurEngine.apply(src, p);
            ui.post(() -> {
                if (g == gen) preview.setProcessed(out);
            });
        });
    }

    private void generateThumbs(final Bitmap base) {
        exec.execute(() -> {
            for (int i = 0; i < BlurEngine.LABELS.length; i++) {
                BlurEngine.Params tp = new BlurEngine.Params();
                tp.style = i;
                final Bitmap out = BlurEngine.apply(base, tp);
                final int idx = i;
                ui.post(() -> thumbs[idx].setImageBitmap(out));
            }
        });
    }

    // ================================================================ import

    private void pickImage() {
        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
        it.setType("image/*");
        it.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(it, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            load(data.getData());
        }
    }

    private void load(final Uri uri) {
        baseName = queryName(uri);
        exec.execute(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) throw new IOException("Tidak bisa membuka file");
                Bitmap bmp;
                try {
                    bmp = BitmapFactory.decodeStream(is);
                } finally {
                    is.close();
                }
                if (bmp == null) throw new IOException("Format gambar tidak didukung");

                final Bitmap full = rotate(uri, bmp);
                final Bitmap sm = scaleTo(full, 720);
                final Bitmap th = scaleTo(full, 120);
                ui.post(() -> {
                    original = full;
                    small = sm;
                    params.cx = 0.5f;
                    params.cy = 0.5f;
                    preview.setCenter(0.5f, 0.5f);
                    preview.setImage(sm);
                    generateThumbs(th);
                    scheduleRender();
                });
            } catch (Exception e) {
                final String m = e.getMessage();
                ui.post(() -> toast("Gagal: " + m));
            }
        });
    }

    private Bitmap rotate(Uri uri, Bitmap bmp) {
        if (Build.VERSION.SDK_INT < 24) return bmp;
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return bmp;
            ExifInterface ex;
            try {
                ex = new ExifInterface(is);
            } finally {
                is.close();
            }
            int o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int deg;
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90: deg = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: deg = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: deg = 270; break;
                default: deg = 0; break;
            }
            if (deg == 0) return bmp;
            Matrix m = new Matrix();
            m.postRotate(deg);
            return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        } catch (Exception e) {
            return bmp;
        }
    }

    private Bitmap scaleTo(Bitmap b, int maxSide) {
        int w = b.getWidth(), h = b.getHeight();
        float s = Math.min(1f, maxSide / (float) Math.max(w, h));
        if (s >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return "image";
    }

    // ================================================================ save

    private void save() {
        if (original == null) {
            toast("Import gambar dulu");
            return;
        }
        if (!hasPermission()) {
            requestPermission();
            return;
        }
        toast("Menyimpan...");
        final BlurEngine.Params p = params.copy();
        final Bitmap src = original;
        final String name = stripExt(baseName) + "_blur.jpg";
        exec.execute(() -> {
            try {
                Bitmap out = BlurEngine.apply(src, p);
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "picablur");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Gagal membuat folder");
                File f = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    out.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                } finally {
                    fos.close();
                }
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
                ui.post(() -> toast("Tersimpan: Pictures/picablur/" + name));
            } catch (Exception e) {
                final String m = e.getMessage();
                ui.post(() -> toast("Gagal simpan: " + m));
            }
        });
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            toast("Izinkan akses file, lalu tekan Download lagi");
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }
    }

    // ================================================================ helpers

    private String stripExt(String name) {
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(0, d) : name;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * dp + 0.5f);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView circle(String s) {
        TextView t = text(s, 20, WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rr(CHIP, 17, 0, 0));
        return t;
    }

    private GradientDrawable rr(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0 && strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }
}
