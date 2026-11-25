package ru.iqwanoino.picablur;

import android.graphics.Bitmap;

/** Mesin blur murni Java: tanpa RenderScript, tanpa library. */
final class BlurEngine {

    static final int GAUSSIAN = 0, ZOOM = 1, MOTION = 2, SPIN = 3, LENS = 4, PIXEL = 5;
    static final String[] LABELS = {
            "Buram Gaussian", "Buram Perbesaran", "Buram Gerakan", "Spin Blur", "Blur Lensa", "Pixelated"
    };

    static final class Params {
        int style = GAUSSIAN;
        int strength = 40;   // 0-100
        int angle = 0;       // 0-359 (khusus gerakan)
        int pusat = 0;       // 0-100 (radius area tajam di sekitar spot)
        float cx = 0.5f;     // posisi spot relatif 0-1
        float cy = 0.5f;
        boolean spot = false; // spot pusat aktif?

        Params copy() {
            Params p = new Params();
            p.style = style;
            p.strength = strength;
            p.angle = angle;
            p.pusat = pusat;
            p.cx = cx;
            p.cy = cy;
            p.spot = spot;
            return p;
        }
    }

    private BlurEngine() { }

    static Bitmap apply(Bitmap src, Params p) {
        int w = src.getWidth(), h = src.getHeight();
        int n = w * h;
        int[] orig = new int[n];
        src.getPixels(orig, 0, w, 0, 0, w, h);
        int[] out = new int[n];

        int maxDim = Math.max(w, h);
        float s = p.strength / 100f;
        // Tanpa spot, pusat efek berada di tengah gambar
        float cx = p.spot ? p.cx * w : w * 0.5f;
        float cy = p.spot ? p.cy * h : h * 0.5f;

        switch (p.style) {
            case ZOOM:
                zoom(orig, out, w, h, s * 0.6f, cx, cy);
                break;
            case MOTION:
                motion(orig, out, w, h, s * maxDim * 0.08f, p.angle);
                break;
            case SPIN:
                spin(orig, out, w, h, s * 0.7f, cx, cy);
                break;
            case LENS:
                gaussian(orig, out, w, h, 1 + (int) (s * maxDim * 0.05f));
                break;
            case PIXEL:
                pixelate(orig, out, w, h, 2 + (int) (s * maxDim * 0.04f));
                break;
            default:
                gaussian(orig, out, w, h, 1 + (int) (s * maxDim * 0.03f));
                break;
        }

        // Spot hanya berlaku jika aktif dan bukan mode gerakan
        if (p.spot && p.style != MOTION) {
            float feather = maxDim * (p.style == LENS ? 0.45f : 0.15f);
            float r = p.pusat / 100f * maxDim * 0.5f;
            mask(orig, out, w, h, cx, cy, r, feather);
        }

        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
    }

    // ------------------------------------------------------------ efek

    /** Gaussian kira-kira: 3x box blur horizontal + vertikal. */
    private static void gaussian(int[] src, int[] dst, int w, int h, int r) {
        if (r < 1) r = 1;
        int[] tmp = new int[w * h];
        System.arraycopy(src, 0, dst, 0, w * h);
        for (int pass = 0; pass < 3; pass++) {
            boxH(dst, tmp, w, h, r);
            boxV(tmp, dst, w, h, r);
        }
    }

    private static void boxH(int[] s, int[] d, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = s[row + clamp(i, w)];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int x = 0; x < w; x++) {
                d[row + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int po = s[row + clamp(x - r, w)];
                int pi = s[row + clamp(x + r + 1, w)];
                sr += ((pi >> 16) & 255) - ((po >> 16) & 255);
                sg += ((pi >> 8) & 255) - ((po >> 8) & 255);
                sb += (pi & 255) - (po & 255);
            }
        }
    }

    private static void boxV(int[] s, int[] d, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = s[clamp(i, h) * w + x];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int y = 0; y < h; y++) {
                d[y * w + x] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int po = s[clamp(y - r, h) * w + x];
                int pi = s[clamp(y + r + 1, h) * w + x];
                sr += ((pi >> 16) & 255) - ((po >> 16) & 255);
                sg += ((pi >> 8) & 255) - ((po >> 8) & 255);
                sb += (pi & 255) - (po & 255);
            }
        }
    }

    /** Perbesaran: sampel ditarik ke arah pusat. */
    private static void zoom(int[] src, int[] dst, int w, int h, float amt, float cx, float cy) {
        final int N = 16;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx, dy = y - cy;
                int sr = 0, sg = 0, sb = 0;
                for (int i = 0; i < N; i++) {
                    float f = 1f - amt * (i / (float) (N - 1));
                    int sx = clamp((int) (cx + dx * f), w);
                    int sy = clamp((int) (cy + dy * f), h);
                    int p = src[sy * w + sx];
                    sr += (p >> 16) & 255;
                    sg += (p >> 8) & 255;
                    sb += p & 255;
                }
                dst[y * w + x] = 0xFF000000 | ((sr / N) << 16) | ((sg / N) << 8) | (sb / N);
            }
        }
    }

    /** Gerakan: sampel sepanjang arah sudut. */
    private static void motion(int[] src, int[] dst, int w, int h, float len, int angleDeg) {
        final int N = 24;
        double a = Math.toRadians(angleDeg);
        float ux = (float) Math.cos(a), uy = (float) Math.sin(a);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sr = 0, sg = 0, sb = 0;
                for (int i = 0; i < N; i++) {
                    float u = (i / (float) (N - 1) - 0.5f) * len;
                    int sx = clamp((int) (x + ux * u), w);
                    int sy = clamp((int) (y + uy * u), h);
                    int p = src[sy * w + sx];
                    sr += (p >> 16) & 255;
                    sg += (p >> 8) & 255;
                    sb += p & 255;
                }
                dst[y * w + x] = 0xFF000000 | ((sr / N) << 16) | ((sg / N) << 8) | (sb / N);
            }
        }
    }

    /** Spin: sampel diputar mengelilingi pusat. range dalam radian. */
    private static void spin(int[] src, int[] dst, int w, int h, float range, float cx, float cy) {
        final int N = 24;
        float[] cs = new float[N], sn = new float[N];
        for (int i = 0; i < N; i++) {
            float ang = (i / (float) (N - 1) - 0.5f) * range;
            cs[i] = (float) Math.cos(ang);
            sn[i] = (float) Math.sin(ang);
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx, dy = y - cy;
                int sr = 0, sg = 0, sb = 0;
                for (int i = 0; i < N; i++) {
                    float fx = cx + dx * cs[i] - dy * sn[i];
                    float fy = cy + dx * sn[i] + dy * cs[i];
                    int sx = clamp((int) fx, w);
                    int sy = clamp((int) fy, h);
                    int p = src[sy * w + sx];
                    sr += (p >> 16) & 255;
                    sg += (p >> 8) & 255;
                    sb += p & 255;
                }
                dst[y * w + x] = 0xFF000000 | ((sr / N) << 16) | ((sg / N) << 8) | (sb / N);
            }
        }
    }

    /** Pixelated: setiap blok diisi warna rata-rata. */
    private static void pixelate(int[] src, int[] dst, int w, int h, int b) {
        if (b < 2) b = 2;
        for (int by = 0; by < h; by += b) {
            int ey = Math.min(by + b, h);
            for (int bx = 0; bx < w; bx += b) {
                int ex = Math.min(bx + b, w);
                int sr = 0, sg = 0, sb = 0, cnt = 0;
                for (int y = by; y < ey; y++) {
                    for (int x = bx; x < ex; x++) {
                        int p = src[y * w + x];
                        sr += (p >> 16) & 255;
                        sg += (p >> 8) & 255;
                        sb += p & 255;
                        cnt++;
                    }
                }
                int c = 0xFF000000 | ((sr / cnt) << 16) | ((sg / cnt) << 8) | (sb / cnt);
                for (int y = by; y < ey; y++) {
                    for (int x = bx; x < ex; x++) {
                        dst[y * w + x] = c;
                    }
                }
            }
        }
    }

    /**
     * Campur gambar asli (di dalam radius r dari pusat) dengan hasil efek
     * (di luar r+feather), dengan transisi halus.
     */
    private static void mask(int[] orig, int[] out, int w, int h,
                             float cx, float cy, float r, float feather) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float dx = x - cx, dy = y - cy;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float t = (d - r) / feather;
                if (t <= 0f) {
                    out[i] = orig[i];
                    continue;
                }
                if (t >= 1f) continue;
                int a = (int) (t * 255);
                int o = orig[i], b = out[i];
                out[i] = 0xFF000000
                        | (mix((o >> 16) & 255, (b >> 16) & 255, a) << 16)
                        | (mix((o >> 8) & 255, (b >> 8) & 255, a) << 8)
                        | mix(o & 255, b & 255, a);
            }
        }
    }

    private static int mix(int o, int b, int a) {
        return (o * (255 - a) + b * a) / 255;
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v >= max ? max - 1 : v);
    }
}
