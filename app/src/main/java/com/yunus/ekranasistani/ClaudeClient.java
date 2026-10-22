package com.yunus.ekranasistani;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Claude Messages API ile konuşan küçük istemci. Tüm metotlar bloklar; arka planda çağrılmalı. */
class ClaudeClient {

    interface Listener {
        /** Akış sırasında, o ana kadar gelen tüm metinle çağrılır. */
        void onText(String fullText);

        void onDone(String fullText);

        /** partialText: hata öncesi gelmiş metin (boş olabilir). */
        void onError(String message, String partialText);
    }

    private static final String URL_MESSAGES = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_ATTEMPTS = 3;

    static final String SYSTEM_PROMPT =
            "Kullanıcı sana telefon ekranının görüntüsünü gönderiyor. Ekrandaki soruyu bul ve cevapla.\n"
            + "- İlk satırda doğrudan cevabı ver.\n"
            + "- Sonra en fazla 2-3 cümlelik kısa bir açıklama yaz.\n"
            + "- Hesap gerekiyorsa yalnızca önemli adımları kısaca göster.\n"
            + "- Markdown veya LaTeX kullanma; düz metin yaz (ör. x^2, sqrt(2), pi).\n"
            + "- Ekranda birden fazla soru varsa numaralandırarak cevapla.\n"
            + "- Görüntü okunamıyorsa ya da ekranda soru yoksa bunu kısaca söyle; emin değilsen belirt.\n"
            + "- Türkçe cevap ver; teknik terimleri gerekirse orijinal dilinde bırak.";

    private volatile HttpURLConnection current;
    private volatile boolean cancelled = false;

    void cancel() {
        cancelled = true;
        HttpURLConnection c = current;
        if (c != null) c.disconnect();
    }

    /** Ekran görüntüsünü gönderir, cevabı akış halinde Listener'a iletir. Geçici hatalarda tekrar dener. */
    void ask(String apiKey, String model, String jpegBase64, Listener l) {
        String body;
        try {
            body = buildBody(model, jpegBase64).toString();
        } catch (JSONException e) {
            l.onError("İstek hazırlanamadı.", "");
            return;
        }

        String lastError = "Bilinmeyen hata";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && !cancelled; attempt++) {
            StringBuilder text = new StringBuilder();
            try {
                Result r = streamOnce(apiKey, body, text, l);
                if (r.ok) {
                    l.onDone(text.toString());
                    return;
                }
                lastError = r.message;
                if (!r.retryable || text.length() > 0) {
                    l.onError(r.message, text.toString());
                    return;
                }
            } catch (IOException e) {
                if (cancelled) return;
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                lastError = "Bağlantı hatası: " + why;
                if (text.length() > 0) {
                    l.onError("Bağlantı koptu, cevap yarım kalmış olabilir. ↻ Tekrar'a bas.", text.toString());
                    return;
                }
            } finally {
                current = null;
            }
            if (attempt < MAX_ATTEMPTS) sleep(800L * attempt);
        }
        if (!cancelled) l.onError(lastError + " (" + MAX_ATTEMPTS + " kez denendi)", "");
    }

    /** Anahtarı ve modeli küçük bir istekle dener. Sorun yoksa null, varsa hata mesajı döner. */
    String test(String apiKey, String model) {
        try {
            JSONObject msg = new JSONObject().put("role", "user").put("content", "Merhaba");
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", 5)
                    .put("messages", new JSONArray().put(msg));
            HttpURLConnection c = open(apiKey, false);
            try {
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                if (code == 200) return null;
                return httpError(code, readAll(c.getErrorStream())).message;
            } finally {
                c.disconnect();
            }
        } catch (JSONException e) {
            return "İstek hazırlanamadı.";
        } catch (IOException e) {
            return "Bağlantı hatası: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static final class Result {
        final boolean ok;
        final boolean retryable;
        final String message;

        Result(boolean ok, boolean retryable, String message) {
            this.ok = ok;
            this.retryable = retryable;
            this.message = message;
        }
    }

    private Result streamOnce(String apiKey, String body, StringBuilder text, Listener l) throws IOException {
        HttpURLConnection c = open(apiKey, true);
        current = c;
        if (cancelled) {
            c.disconnect();
            return new Result(false, false, "İptal edildi.");
        }
        try {
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) return httpError(code, readAll(c.getErrorStream()));

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) continue;
                    JSONObject ev;
                    try {
                        ev = new JSONObject(payload);
                    } catch (JSONException e) {
                        continue;
                    }
                    String type = ev.optString("type");
                    if ("content_block_delta".equals(type)) {
                        JSONObject d = ev.optJSONObject("delta");
                        if (d != null && "text_delta".equals(d.optString("type"))) {
                            text.append(d.optString("text"));
                            l.onText(text.toString());
                        }
                    } else if ("message_stop".equals(type)) {
                        return new Result(true, false, null);
                    } else if ("error".equals(type)) {
                        JSONObject err = ev.optJSONObject("error");
                        String et = err == null ? "" : err.optString("type");
                        String em = err == null ? "" : err.optString("message");
                        boolean retry = "overloaded_error".equals(et)
                                || "api_error".equals(et)
                                || "rate_limit_error".equals(et);
                        return new Result(false, retry, "Claude hatası (" + et + "): " + em);
                    }
                }
            }
            throw new IOException("Akış beklenmedik şekilde bitti");
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String apiKey, boolean stream) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_MESSAGES).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        c.setRequestProperty("x-api-key", apiKey);
        c.setRequestProperty("anthropic-version", API_VERSION);
        if (stream) c.setRequestProperty("accept", "text/event-stream");
        return c;
    }

    private static JSONObject buildBody(String model, String b64) throws JSONException {
        JSONObject source = new JSONObject()
                .put("type", "base64")
                .put("media_type", "image/jpeg")
                .put("data", b64);
        JSONObject image = new JSONObject().put("type", "image").put("source", source);
        JSONObject prompt = new JSONObject().put("type", "text").put("text", "Ekrandaki soruyu cevapla.");
        JSONObject msg = new JSONObject()
                .put("role", "user")
                .put("content", new JSONArray().put(image).put(prompt));
        return new JSONObject()
                .put("model", model)
                .put("max_tokens", 1024)
                .put("stream", true)
                .put("system", SYSTEM_PROMPT)
                .put("messages", new JSONArray().put(msg));
    }

    private static Result httpError(int code, String raw) {
        String apiMsg = "";
        try {
            JSONObject e = new JSONObject(raw).optJSONObject("error");
            if (e != null) apiMsg = e.optString("message");
        } catch (Exception ignored) {
        }
        String lower = apiMsg.toLowerCase(Locale.ROOT);
        switch (code) {
            case 401:
                return new Result(false, false, "API anahtarı geçersiz. Uygulamayı açıp anahtarı kontrol et.");
            case 403:
                return new Result(false, false, "Bu anahtarın erişim izni yok: " + apiMsg);
            case 404:
                return new Result(false, false, "Model bulunamadı: " + apiMsg);
            case 413:
                return new Result(false, false, "Görüntü çok büyük.");
            case 429:
                return new Result(false, true, "Çok sık istek gönderildi (hız sınırı).");
            case 400:
                if (lower.contains("credit")) {
                    return new Result(false, false, "API kredin bitmiş. platform.claude.com → Billing'den kredi yükle.");
                }
                return new Result(false, false, "İstek reddedildi: " + apiMsg);
            default:
                if (code >= 500) {
                    return new Result(false, true, "Claude sunucusu şu an yoğun ya da hatalı (" + code + ").");
                }
                return new Result(false, false, "HTTP " + code + ": " + apiMsg);
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
