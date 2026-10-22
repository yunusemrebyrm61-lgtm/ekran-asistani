package com.yunus.ekranasistani;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ekranı yakalar, yüzen butonu ve cevap panelini gösterir.
 * Yüzen butona dokunulunca o anki ekranı Claude'a gönderir.
 */
public class CaptureService extends Service {

    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String ACTION_STOP = "com.yunus.ekranasistani.STOP";

    private static final String CHANNEL = "ekran_asistani";
    private static final int NOTIF_ID = 1;
    /** Gönderilen görüntünün uzun kenarı (piksel). Okunabilirlik ile hız/maliyet dengesi. */
    private static final int MAX_SIDE = 1568;

    static volatile boolean running = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Object frameLock = new Object();
    private final ClaudeClient client = new ClaudeClient();

    // Ekran yakalama
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread frameThread;
    private Handler frameHandler;
    private Image latest;
    private int capW, capH, capDpi;

    // Ekran üstü pencereler
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private MaxHeightScroll answerScroll;
    private TextView panelTitle;
    private TextView answerView;
    private boolean panelShown = false;

    private volatile boolean busy = false;
    private volatile boolean destroyed = false;
    private volatile String lastImage;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } catch (Exception e) {
            toast("Servis başlatılamadı: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection != null) return START_NOT_STICKY; // zaten çalışıyor

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) {
            toast("Ekran izni alınamadı, tekrar dene.");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            toast("Ekran izni alınamadı, tekrar dene.");
            stopSelf();
            return START_NOT_STICKY;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (!destroyed) {
                    toast("Ekran paylaşımı sona erdi. Uygulamayı açıp tekrar Başlat'a bas.");
                    stopSelf();
                }
            }
        }, main);

        try {
            startCapture();
            showOverlays();
        } catch (Exception e) {
            toast("Başlatılamadı: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        return START_NOT_STICKY;
    }

    // ---------------------------------------------------------------- ekran yakalama

    private void startCapture() {
        frameThread = new HandlerThread("ekran-kare");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
        measureScreen();
        ImageReader r = newReader(capW, capH);
        synchronized (frameLock) {
            reader = r;
        }
        display = projection.createVirtualDisplay("EkranAsistani", capW, capH, capDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.getSurface(), null, frameHandler);
    }

    private void measureScreen() {
        Rect b = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        capW = b.width();
        capH = b.height();
        capDpi = getResources().getDisplayMetrics().densityDpi;
    }

    private ImageReader newReader(int w, int h) {
        ImageReader r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        r.setOnImageAvailableListener(rd -> {
            Image img;
            try {
                img = rd.acquireLatestImage();
            } catch (Exception e) {
                return;
            }
            if (img == null) return;
            synchronized (frameLock) {
                if (rd != reader) { // eski okuyucudan gelen kare
                    img.close();
                    return;
                }
                if (latest != null) latest.close();
                latest = img;
            }
        }, frameHandler);
        return r;
    }

    /** En son ekran karesini Bitmap olarak verir; yoksa null. */
    private Bitmap grabFrame() {
        synchronized (frameLock) {
            if (latest == null) return null;
            try {
                Image.Plane p = latest.getPlanes()[0];
                int w = latest.getWidth();
                int h = latest.getHeight();
                int pixelStride = p.getPixelStride();
                int rowStride = p.getRowStride();
                ByteBuffer buf = p.getBuffer();
                buf.rewind();
                int needed = rowStride * h;
                if (buf.remaining() < needed) { // bazı cihazlarda son satır kısa geliyor
                    ByteBuffer padded = ByteBuffer.allocate(needed);
                    padded.put(buf);
                    padded.rewind();
                    buf = padded;
                }
                Bitmap full = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888);
                full.copyPixelsFromBuffer(buf);
                if (full.getWidth() == w) return full;
                Bitmap cropped = Bitmap.createBitmap(full, 0, 0, w, h);
                full.recycle();
                return cropped;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static String toJpegBase64(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(w, h));
        Bitmap s = bmp;
        if (scale < 1f) {
            s = Bitmap.createScaledBitmap(bmp, Math.round(w * scale), Math.round(h * scale), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.JPEG, 85, out);
        if (s != bmp) s.recycle();
        bmp.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (display == null || destroyed) return;
        int oldW = capW, oldH = capH;
        measureScreen();
        if (capW == oldW && capH == oldH) return;
        try {
            // Ekran döndü: yeni boyutta okuyucu oluştur, sanal ekranı ona bağla.
            ImageReader fresh = newReader(capW, capH);
            ImageReader old;
            synchronized (frameLock) {
                old = reader;
                reader = fresh;
                if (latest != null) {
                    latest.close();
                    latest = null;
                }
            }
            display.resize(capW, capH, capDpi);
            display.setSurface(fresh.getSurface());
            if (old != null) old.close();
        } catch (Exception ignored) {
        }
        relayoutOverlays();
    }

    // ---------------------------------------------------------------- soru sorma

    private void onAsk() {
        if (busy) {
            toast("Önceki cevap bekleniyor…");
            return;
        }
        busy = true;
        final long t0 = SystemClock.elapsedRealtime();
        // Buton ve panel görüntüye girmesin diye kısa süre gizle.
        hidePanel();
        bubble.setVisibility(View.INVISIBLE);
        main.postDelayed(() -> exec.execute(() -> {
            Bitmap bmp = grabFrame();
            main.post(() -> {
                if (!destroyed) bubble.setVisibility(View.VISIBLE);
            });
            if (bmp == null) {
                finish(t0, null, "Ekran görüntüsü alınamadı. Ekranı biraz kaydırıp tekrar dene.");
                return;
            }
            String img = toJpegBase64(bmp);
            lastImage = img;
            send(img, t0);
        }), 300);
    }

    private void retry() {
        if (busy) return;
        final String img = lastImage;
        if (img == null) {
            onAsk();
            return;
        }
        busy = true;
        final long t0 = SystemClock.elapsedRealtime();
        exec.execute(() -> send(img, t0));
    }

    /** Arka plan iş parçacığında çalışır. */
    private void send(String img, long t0) {
        main.post(() -> {
            if (destroyed) return;
            panelTitle.setText("Claude düşünüyor…");
            answerView.setText("");
            showPanel();
        });
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String key = prefs.getString(MainActivity.KEY_API, "");
        String model = prefs.getString(MainActivity.KEY_MODEL, MainActivity.MODEL_SONNET);
        if (key.isEmpty()) {
            finish(t0, null, "API anahtarı girilmemiş. Uygulamayı açıp anahtarı gir.");
            return;
        }
        client.ask(key, model, img, new ClaudeClient.Listener() {
            @Override
            public void onText(String fullText) {
                main.post(() -> {
                    if (!destroyed) answerView.setText(fullText);
                });
            }

            @Override
            public void onDone(String fullText) {
                finish(t0, fullText, null);
            }

            @Override
            public void onError(String message, String partialText) {
                finish(t0, partialText, message);
            }
        });
    }

    private void finish(long t0, String text, String error) {
        final double secs = (SystemClock.elapsedRealtime() - t0) / 1000.0;
        main.post(() -> {
            busy = false;
            if (destroyed) return;
            if (error == null) {
                panelTitle.setText(String.format(Locale.forLanguageTag("tr"), "✓ %.1f sn", secs));
                answerView.setText(text);
            } else {
                panelTitle.setText("⚠ Hata");
                boolean hasText = text != null && !text.isEmpty();
                answerView.setText(hasText ? text + "\n\n⚠ " + error : error);
            }
            answerScroll.scrollTo(0, 0);
            showPanel();
        });
    }

    // ---------------------------------------------------------------- ekran üstü pencereler

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void showOverlays() {
        wm = getSystemService(WindowManager.class);

        bubble = new TextView(this);
        bubble.setText("?");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xE6D97757);
        bubble.setBackground(circle);

        int size = dp(56);
        bubbleLp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = capW - size - dp(8);
        bubbleLp.y = capH / 3;
        bubble.setOnTouchListener(new BubbleTouch());
        wm.addView(bubble, bubbleLp);

        buildPanel();
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        panel.setPadding(p, dp(6), p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2202124);
        bg.setCornerRadius(dp(16));
        panel.setBackground(bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panelTitle = new TextView(this);
        panelTitle.setText("Henüz soru yok");
        panelTitle.setTextColor(0xFFBBBBBB);
        panelTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.addView(panelTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(headerButton("↻ Tekrar", v -> retry()));
        header.addView(headerButton("✕", v -> hidePanel()));
        panel.addView(header);

        answerView = new TextView(this);
        answerView.setTextColor(Color.WHITE);
        answerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        answerView.setLineSpacing(0f, 1.15f);
        answerScroll = new MaxHeightScroll(this, (int) (capH * 0.38f));
        answerScroll.addView(answerView);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        panel.addView(answerScroll, slp);

        panelLp = new WindowManager.LayoutParams(
                capW - 2 * dp(12), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        panelLp.y = dp(24);
    }

    private TextView headerButton(String label, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        b.setOnClickListener(l);
        return b;
    }

    private void showPanel() {
        if (panelShown || destroyed || wm == null) return;
        try {
            wm.addView(panel, panelLp);
            panelShown = true;
        } catch (Exception ignored) {
        }
    }

    private void hidePanel() {
        if (!panelShown) return;
        try {
            wm.removeView(panel);
        } catch (Exception ignored) {
        }
        panelShown = false;
    }

    private void togglePanel() {
        if (panelShown) hidePanel();
        else showPanel();
    }

    private void relayoutOverlays() {
        if (panelLp == null || bubble == null) return;
        panelLp.width = capW - 2 * dp(12);
        answerScroll.maxH = (int) (capH * 0.38f);
        answerScroll.requestLayout();
        if (panelShown) safeUpdate(panel, panelLp);
        int size = dp(56);
        bubbleLp.x = Math.max(0, Math.min(bubbleLp.x, capW - size));
        bubbleLp.y = Math.max(0, Math.min(bubbleLp.y, capH - size));
        safeUpdate(bubble, bubbleLp);
    }

    private void safeUpdate(View v, WindowManager.LayoutParams lp) {
        try {
            wm.updateViewLayout(v, lp);
        } catch (Exception ignored) {
        }
    }

    /** Dokun = sor, sürükle = taşı, basılı tut = paneli gizle/göster. */
    private class BubbleTouch implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(CaptureService.this).getScaledTouchSlop();
        private int startX, startY;
        private float downX, downY;
        private long downAt;
        private boolean dragging;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = bubbleLp.x;
                    startY = bubbleLp.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    downAt = SystemClock.uptimeMillis();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) dragging = true;
                    if (dragging) {
                        bubbleLp.x = startX + (int) dx;
                        bubbleLp.y = startY + (int) dy;
                        safeUpdate(bubble, bubbleLp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        if (SystemClock.uptimeMillis() - downAt > 600) togglePanel();
                        else onAsk();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    static class MaxHeightScroll extends ScrollView {
        int maxH;

        MaxHeightScroll(Context c, int maxH) {
            super(c);
            this.maxH = maxH;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
        }
    }

    // ---------------------------------------------------------------- bildirim ve kapanış

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Ekran Asistanı", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Ekran Asistanı çalışıyor")
                .setContentText("Soru sormak için yüzen butona dokun.")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((Icon) null, "Durdur", stop).build())
                .build();
    }

    private void toast(String s) {
        main.post(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        client.cancel();
        exec.shutdownNow();
        main.removeCallbacksAndMessages(null);
        hidePanel();
        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception ignored) {
            }
        }
        synchronized (frameLock) {
            if (latest != null) {
                latest.close();
                latest = null;
            }
        }
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
        }
        if (frameThread != null) frameThread.quitSafely();
        super.onDestroy();
    }
}
