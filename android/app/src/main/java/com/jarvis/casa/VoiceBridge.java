package com.jarvis.casa;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ponte nativa de voz usada pelo index.html (window.VoiceBridge).
 *
 * Fala: TextToSpeech do Android, preferindo o motor do Google e a melhor voz pt-BR
 * (vozes de rede/"neurais" quando há internet), em frases curtas com pequenas pausas,
 * para soar natural e suave.
 * Escuta: SpeechRecognizer nativo (o WebView não tem reconhecimento de voz).
 *
 * Eventos enviados ao JavaScript: window.__voice(tipo, texto)
 *   state(listening) | partial | result | error(permission|unavailable|nomatch|busy|network)
 *   tts_end | pause | resume | ftest (aviso para o usuário)
 */
public class VoiceBridge {
    static final int REQ_MIC = 73;
    private static final String GOOGLE_TTS = "com.google.android.tts";

    private final MainActivity act;
    private final WebView web;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final AudioManager am;

    private boolean destroyed = false;

    // ---- fala ----
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsFailed = false;
    private boolean triedDefaultEngine = false;
    private boolean langWarned = false;
    private String pendingSpeak = null;
    private volatile String voicesJson = "[]";
    private String voiceName = "";
    private float pitch = 1.0f;
    private float rate = 0.95f;
    private int gen = 0;
    private String lastId = "";
    private String lastText = "";
    private boolean retried = false;
    private boolean forceLocal = false;
    private boolean focus = false;

    // ---- escuta ----
    private SpeechRecognizer sr;

    public VoiceBridge(MainActivity act, WebView web) {
        this.act = act;
        this.web = web;
        this.am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
        h.post(this::initTts);
    }

    // =====================================================================
    //  API chamada pelo JavaScript
    // =====================================================================

    @JavascriptInterface
    public void speak(final String text) {
        h.post(() -> speakInternal(text, false));
    }

    @JavascriptInterface
    public void stopSpeaking() {
        h.post(this::stopSpeakInternal);
    }

    @JavascriptInterface
    public void listen() {
        h.post(() -> startListen(false));
    }

    @JavascriptInterface
    public void listenQuiet() {
        h.post(() -> startListen(true));
    }

    @JavascriptInterface
    public void stopListening() {
        h.post(this::stopListenInternal);
    }

    @JavascriptInterface
    public String voices() {
        return voicesJson;
    }

    @JavascriptInterface
    public void setVoice(final String name, final double p, final double r) {
        h.post(() -> {
            voiceName = name == null ? "" : name;
            pitch = (float) Math.max(0.5, Math.min(1.5, p));
            rate = (float) Math.max(0.6, Math.min(1.4, r));
            applyVoice();
        });
    }

    @JavascriptInterface
    public void keepAwake(final boolean on) {
        act.runOnUiThread(() -> act.setVoiceKeep(on));
    }

    // =====================================================================
    //  Ciclo de vida (chamado pela MainActivity)
    // =====================================================================

    public void onActivityPause() {
        stopListenInternal();
        stopSpeakInternal();
        emit("pause", "");
    }

    public void onActivityResume() {
        emit("resume", "");
    }

    public void shutdown() {
        destroyed = true;
        stopListenInternal();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) { }
        tts = null;
        abandonFocus();
    }

    // =====================================================================
    //  Fala (TextToSpeech)
    // =====================================================================

    private boolean googleTtsInstalled() {
        try {
            act.getPackageManager().getPackageInfo(GOOGLE_TTS, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void initTts() {
        if (tts != null || destroyed) return;
        try {
            if (!triedDefaultEngine && googleTtsInstalled()) {
                tts = new TextToSpeech(act, this::onTtsInit, GOOGLE_TTS);
            } else {
                tts = new TextToSpeech(act, this::onTtsInit);
            }
        } catch (Exception e) {
            ttsFailed = true;
        }
    }

    private void onTtsInit(int status) {
        if (destroyed) return;
        if (status != TextToSpeech.SUCCESS) {
            if (!triedDefaultEngine) {
                // motor do Google falhou: tenta o motor padrão do aparelho
                triedDefaultEngine = true;
                try {
                    if (tts != null) tts.shutdown();
                } catch (Exception ignored) { }
                tts = null;
                initTts();
                return;
            }
            ttsFailed = true;
            ttsReady = false;
            if (pendingSpeak != null) {
                pendingSpeak = null;
                emit("ftest", "Este aparelho não tem mecanismo de voz ativo. Ative em Configurações > Idioma > Saída de texto para fala.");
                emit("tts_end", "");
            }
            return;
        }
        try {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
        } catch (Exception ignored) { }
        int r = tts.setLanguage(Locale.forLanguageTag("pt-BR"));
        if ((r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) && !langWarned) {
            langWarned = true;
            emit("ftest", "Falta a voz em português. Instale em Configurações > Idioma > Saída de texto para fala > Google > Instalar dados de voz.");
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(final String id) { h.post(() -> ttsDone(id)); }
            @Override public void onError(final String id) { h.post(() -> ttsError(id)); }
            @Override public void onError(final String id, int code) { h.post(() -> ttsError(id)); }
            @Override public void onStop(String id, boolean interrupted) { }
        });
        ttsReady = true;
        buildVoices();
        applyVoice();
        if (pendingSpeak != null) {
            String t = pendingSpeak;
            pendingSpeak = null;
            speakInternal(t, false);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    /** Vozes em português, da melhor para a pior (pt-BR primeiro, maior qualidade primeiro). */
    private List<Voice> ptVoices() {
        List<Voice> out = new ArrayList<>();
        try {
            Set<Voice> all = tts.getVoices();
            if (all == null) return out;
            for (Voice v : all) {
                Locale l = v.getLocale();
                if (l == null || !"pt".equalsIgnoreCase(l.getLanguage())) continue;
                Set<String> f = v.getFeatures();
                if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                out.add(v);
            }
        } catch (Exception ignored) { }
        Collections.sort(out, new Comparator<Voice>() {
            @Override public int compare(Voice a, Voice b) {
                int ba = "BR".equalsIgnoreCase(a.getLocale().getCountry()) ? 1 : 0;
                int bb = "BR".equalsIgnoreCase(b.getLocale().getCountry()) ? 1 : 0;
                if (ba != bb) return bb - ba;
                if (a.getQuality() != b.getQuality()) return b.getQuality() - a.getQuality();
                int na = a.isNetworkConnectionRequired() ? 1 : 0;
                int nb = b.isNetworkConnectionRequired() ? 1 : 0;
                return nb - na;
            }
        });
        return out;
    }

    private void buildVoices() {
        try {
            JSONArray arr = new JSONArray();
            for (Voice v : ptVoices()) {
                JSONObject o = new JSONObject();
                o.put("name", v.getName());
                o.put("loc", v.getLocale().toLanguageTag());
                o.put("net", v.isNetworkConnectionRequired());
                arr.put(o);
            }
            voicesJson = arr.toString();
        } catch (Exception e) {
            voicesJson = "[]";
        }
    }

    private boolean currentNeedsNet = false;

    private void applyVoice() {
        if (tts == null || !ttsReady) return;
        try {
            boolean netOk = !forceLocal && online();
            List<Voice> list = ptVoices();
            Voice chosen = null;
            if (!voiceName.isEmpty()) {
                for (Voice v : list) {
                    if (v.getName().equals(voiceName) && (netOk || !v.isNetworkConnectionRequired())) {
                        chosen = v;
                        break;
                    }
                }
            }
            if (chosen == null) {
                for (Voice v : list) {
                    if (netOk || !v.isNetworkConnectionRequired()) {
                        chosen = v;
                        break;
                    }
                }
            }
            if (chosen != null) {
                tts.setVoice(chosen);
                currentNeedsNet = chosen.isNetworkConnectionRequired();
            } else {
                currentNeedsNet = false;
            }
            tts.setPitch(pitch);
            tts.setSpeechRate(rate);
        } catch (Exception ignored) { }
    }

    private static List<String> splitText(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String p : s.split("(?<=[.!?\u2026;])\\s+")) {
            p = p.trim();
            if (p.isEmpty()) continue;
            if (cur.length() > 0 && cur.length() + 1 + p.length() > 170) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(p);
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.isEmpty()) out.add(s);
        // protege contra textos gigantes sem pontuação (limite do motor ~4000)
        List<String> safe = new ArrayList<>();
        for (String c : out) {
            while (c.length() > 3500) {
                int cut = c.lastIndexOf(' ', 3500);
                if (cut < 1) cut = 3500;
                safe.add(c.substring(0, cut));
                c = c.substring(cut).trim();
            }
            if (!c.isEmpty()) safe.add(c);
        }
        return safe;
    }

    private void speakInternal(String text, boolean isRetry) {
        if (destroyed) return;
        if (text == null || text.trim().isEmpty()) {
            emit("tts_end", "");
            return;
        }
        if (ttsFailed) {
            emit("tts_end", "");
            return;
        }
        if (!ttsReady) {
            pendingSpeak = text;
            initTts();
            return;
        }
        if (!isRetry) {
            retried = false;
            forceLocal = false;
        }
        lastText = text;
        gen++;
        applyVoice();
        try {
            tts.stop();
        } catch (Exception ignored) { }
        requestFocus();
        List<String> chunks = splitText(text);
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        final int g = gen;
        try {
            for (int i = 0; i < chunks.size(); i++) {
                String id = g + ":" + i;
                if (i > 0) tts.playSilentUtterance(160, TextToSpeech.QUEUE_ADD, g + ":s" + i);
                int rc = tts.speak(chunks.get(i), i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, id);
                if (rc != TextToSpeech.SUCCESS) {
                    abandonFocus();
                    emit("tts_end", "");
                    return;
                }
                lastId = id;
            }
        } catch (Exception e) {
            abandonFocus();
            emit("tts_end", "");
        }
    }

    private void stopSpeakInternal() {
        gen++; // invalida callbacks da fala anterior (não envia tts_end)
        pendingSpeak = null;
        try {
            if (tts != null) tts.stop();
        } catch (Exception ignored) { }
        abandonFocus();
    }

    private void ttsDone(String id) {
        if (destroyed || id == null || !id.equals(lastId)) return;
        abandonFocus();
        emit("tts_end", "");
    }

    private void ttsError(String id) {
        if (destroyed || id == null || !id.startsWith(gen + ":")) return;
        if (!retried && currentNeedsNet) {
            // voz de rede falhou (sem internet?): refaz com a melhor voz local
            retried = true;
            forceLocal = true;
            speakInternal(lastText, true);
            return;
        }
        if (id.equals(lastId)) {
            abandonFocus();
            emit("tts_end", "");
        }
    }

    @SuppressWarnings("deprecation")
    private void requestFocus() {
        if (focus || am == null) return;
        try {
            focus = am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (!focus || am == null) return;
        try {
            am.abandonAudioFocus(null);
        } catch (Exception ignored) { }
        focus = false;
    }

    // =====================================================================
    //  Escuta (SpeechRecognizer)
    // =====================================================================

    private void startListen(boolean quiet) {
        if (destroyed) return;
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            emit("error", "unavailable");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            emit("error", "permission");
            return;
        }
        stopListenInternal();
        final SpeechRecognizer mine;
        try {
            mine = SpeechRecognizer.createSpeechRecognizer(act);
        } catch (Exception e) {
            emit("error", "unavailable");
            return;
        }
        sr = mine;
        mine.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (mine == sr) emit("state", "listening");
            }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int eventType, Bundle params) { }

            @Override public void onPartialResults(Bundle partial) {
                if (mine != sr) return;
                String t = firstResult(partial);
                if (!t.isEmpty()) emit("partial", t);
            }

            @Override public void onResults(Bundle results) {
                if (mine != sr) return;
                String t = firstResult(results);
                stopListenInternal();
                if (t.isEmpty()) emit("error", "nomatch");
                else emit("result", t);
            }

            @Override public void onError(int code) {
                if (mine != sr) return;
                stopListenInternal();
                emit("error", mapError(code));
            }
        });

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L);
        try {
            mine.startListening(i);
        } catch (Exception e) {
            stopListenInternal();
            emit("error", "nomatch");
        }
    }

    private static String firstResult(Bundle b) {
        if (b == null) return "";
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (l == null || l.isEmpty() || l.get(0) == null) return "";
        return l.get(0).trim();
    }

    private static String mapError(int code) {
        switch (code) {
            case 9:  return "permission";   // ERROR_INSUFFICIENT_PERMISSIONS
            case 6:                         // ERROR_SPEECH_TIMEOUT
            case 7:  return "nomatch";      // ERROR_NO_MATCH
            case 8:  return "busy";         // ERROR_RECOGNIZER_BUSY
            case 1:                         // ERROR_NETWORK_TIMEOUT
            case 2:                         // ERROR_NETWORK
            case 4:  return "network";      // ERROR_SERVER
            case 12:                        // ERROR_LANGUAGE_NOT_SUPPORTED
            case 13: return "unavailable";  // ERROR_LANGUAGE_UNAVAILABLE
            default: return "nomatch";
        }
    }

    private void stopListenInternal() {
        SpeechRecognizer s = sr;
        sr = null; // callbacks antigos passam a ser ignorados
        if (s == null) return;
        try {
            s.cancel();
        } catch (Exception ignored) { }
        try {
            s.destroy();
        } catch (Exception ignored) { }
    }

    // =====================================================================
    //  Envio de eventos ao JavaScript
    // =====================================================================

    private void emit(String type, String p) {
        if (destroyed) return;
        final String js = "window.__voice&&window.__voice(" + JSONObject.quote(type) + ","
                + JSONObject.quote(p == null ? "" : p) + ")";
        h.post(() -> {
            if (!destroyed && web != null) web.evaluateJavascript(js, null);
        });
    }
}
