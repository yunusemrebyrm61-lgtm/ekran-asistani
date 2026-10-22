package com.yunus.ekranasistani;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    static final String PREFS = "ayarlar";
    static final String KEY_API = "api_key";
    static final String KEY_MODEL = "model";
    static final String MODEL_SONNET = "claude-sonnet-5-5";
    static final String MODEL_HAIKU = "claude-haiku-5-5";

    private static final int REQ_CAPTURE = 1;
    private static final int REQ_NOTIF = 2;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private EditText keyInput;
    private RadioGroup modelGroup;
    private RadioButton sonnetBtn;
    private RadioButton haikuBtn;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (CaptureService.running) setStatus("Çalışıyor. Yüzen butona dokunarak soru sorabilirsin.");
    }

    @Override
    protected void onDestroy() {
        exec.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- arayüz

    private View buildUi() {
        final int pad = dp(20);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView title = text("Ekran Asistanı", 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(text("Yüzen butona dokun → ekran görüntüsü Claude'a gider → cevap altta çıkar. "
                + "Butonu sürükleyerek taşıyabilirsin; basılı tutarsan cevap paneli gizlenir/görünür.", 14), lp(8));

        col.addView(text("Claude API anahtarı", 16), lp(20));
        keyInput = new EditText(this);
        keyInput.setHint("sk-ant-...");
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setText(prefs.getString(KEY_API, ""));
        col.addView(keyInput, lp(4));

        col.addView(text("Model", 16), lp(16));
        modelGroup = new RadioGroup(this);
        sonnetBtn = new RadioButton(this);
        sonnetBtn.setId(View.generateViewId());
        sonnetBtn.setText("Sonnet 5.5 — daha doğru (önerilen)");
        haikuBtn = new RadioButton(this);
        haikuBtn.setId(View.generateViewId());
        haikuBtn.setText("Haiku 5.5 — daha hızlı ve ucuz");
        modelGroup.addView(sonnetBtn);
        modelGroup.addView(haikuBtn);
        boolean haiku = MODEL_HAIKU.equals(prefs.getString(KEY_MODEL, MODEL_SONNET));
        modelGroup.check(haiku ? haikuBtn.getId() : sonnetBtn.getId());
        col.addView(modelGroup, lp(4));

        col.addView(button("Anahtarı test et", v -> testKey()), lp(20));
        col.addView(button("Başlat", v -> start()), lp(8));
        col.addView(button("Durdur", v -> {
            stopService(new Intent(this, CaptureService.class));
            setStatus("Durduruldu.");
        }), lp(8));
        col.addView(button("Pil kısıtlamasını kaldır (önerilen)", v -> askBatteryExemption()), lp(8));

        status = text("", 15);
        col.addView(status, lp(16));

        col.addView(text("Başlat'a bastığında telefon ekran paylaşımı izni ister. "
                + "Uygulamayı her başlattığında bu izin bir kez sorulur; her soru için değil.", 13), lp(16));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return WindowInsets.CONSUMED;
        });
        return scroll;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    // ---------------------------------------------------------------- işlemler

    private String selectedModel() {
        return modelGroup.getCheckedRadioButtonId() == haikuBtn.getId() ? MODEL_HAIKU : MODEL_SONNET;
    }

    private boolean saveSettings() {
        String key = keyInput.getText().toString().trim();
        if (key.isEmpty()) {
            setStatus("Önce API anahtarını gir.");
            return false;
        }
        prefs.edit().putString(KEY_API, key).putString(KEY_MODEL, selectedModel()).apply();
        return true;
    }

    private void testKey() {
        if (!saveSettings()) return;
        final String key = prefs.getString(KEY_API, "");
        final String model = selectedModel();
        setStatus("Test ediliyor…");
        exec.execute(() -> {
            String err = new ClaudeClient().test(key, model);
            main.post(() -> setStatus(err == null ? "✓ Anahtar ve model çalışıyor." : "✗ " + err));
        });
    }

    private void start() {
        if (!saveSettings()) return;
        if (CaptureService.running) {
            setStatus("Zaten çalışıyor. Yeniden başlatmak için önce Durdur'a bas.");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            setStatus("\"Diğer uygulamaların üzerinde göster\" iznini aç, sonra geri dönüp tekrar Başlat'a bas.");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        Intent consent = Build.VERSION.SDK_INT >= 34
                ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
        startActivityForResult(consent, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Ekran paylaşımı izni verilmedi.");
            return;
        }
        Intent svc = new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(svc);
        setStatus("Çalışıyor. Yüzen butona dokunarak soru sorabilirsin.");
        moveTaskToBack(true);
    }

    private void askBatteryExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            setStatus("Pil kısıtlaması zaten kaldırılmış.");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}
