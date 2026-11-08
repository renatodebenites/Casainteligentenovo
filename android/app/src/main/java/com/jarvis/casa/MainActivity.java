package com.jarvis.casa;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    static final int REQ_AUDIO = 71;
    static final int REQ_NOTIF = 72;

    WebView web;
    VoiceBridge voice;
    boolean voiceKeep = false;
    boolean wall = false;
    int lightFlags = 0;
    boolean loaded = false;
    String pendingNev;
    PermissionRequest pendingPerm;
    final Handler h = new Handler(Looper.getMainLooper());

    // Sincroniza tela cheia (modo parede / Modo Alexa / alerta) e mantém a tela ligada
    final Runnable syncWall = new Runnable() {
        @Override public void run() {
            if (web != null) {
                web.evaluateJavascript(
                    "(function(){try{var b=document.body,v=document.getElementById('alexa-view');"
                    + "var on=!!(v&&v.classList.contains('on'));"
                    + "var imm=!!((b&&b.classList.contains('wallon'))||document.querySelector('#dashwrap.full')||(on&&(v.classList.contains('ax-full')||v.classList.contains('ax-alerting'))));"
                    + "return (imm?'1':'0')+((on||imm)?'1':'0')}catch(e){return '00'}})()",
                    v -> {
                        String r = v == null ? "" : v.replace("\"", "");
                        boolean imm = r.length() > 0 && r.charAt(0) == '1';
                        boolean keep = r.length() > 1 && r.charAt(1) == '1';
                        if (imm != wall) setWall(imm);
                        setKeepOn(keep || voiceKeep);
                    });
            }
            h.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);

        web.addJavascriptInterface(new FrigatePushBridge(this), "FrigatePushBridge");
        voice = new VoiceBridge(this, web);
        web.addJavascriptInterface(voice, "VoiceBridge");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(() -> {
                    boolean audio = false;
                    for (String res : r.getResources())
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) audio = true;
                    if (audio && Build.VERSION.SDK_INT >= 23
                            && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        pendingPerm = r;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
                    } else {
                        r.grant(r.getResources());
                    }
                });
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme();
                if ("file".equals(sc)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                loaded = true;
                if (pendingNev != null) {
                    deliverNev(pendingNev);
                    pendingNev = null;
                }
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (url != null && url.startsWith("http")) {
                String fn = android.webkit.URLUtil.guessFileName(url, cd, mime);
                FrigatePushBridge.enqueueDownload(this, url, fn);
            }
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        web.loadUrl("file:///android_asset/www/index.html");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    void handleIntent(Intent i) {
        if (i == null) return;
        String nev = i.getStringExtra("nev");
        if (nev == null) return;
        i.removeExtra("nev");
        if (loaded) deliverNev(nev); else pendingNev = nev;
    }

    // Abre o evento tocado na notificação dentro da página
    void deliverNev(String json) {
        if (web == null || json == null) return;
        web.evaluateJavascript(
            "try{var j=" + json + ";window.__nev=j;if(typeof openFromNotif==='function')openFromNotif(j)}catch(e){}",
            null);
    }

    void setWall(boolean on) {
        wall = on;
        applyUi();
    }

    void setVoiceKeep(boolean on) {
        voiceKeep = on;
        setKeepOn(on);
    }

    void setKeepOn(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @SuppressWarnings("deprecation")
    void applyUi() {
        int f = lightFlags;
        if (wall) {
            f |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        }
        getWindow().getDecorView().setSystemUiVisibility(f);
    }

    @SuppressWarnings("deprecation")
    void setBars(String hex) {
        try {
            int c = Color.parseColor(hex);
            getWindow().setStatusBarColor(c);
            getWindow().setNavigationBarColor(c);
            double lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
            lightFlags = 0;
            if (lum > 0.6) {
                lightFlags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) lightFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            applyUi();
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_AUDIO && pendingPerm != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
                pendingPerm.grant(pendingPerm.getResources());
            else pendingPerm.deny();
            pendingPerm = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript(
            "(function(){try{return (window.__axBack&&window.__axBack())?'1':'0'}catch(e){return '0'}})()",
            v -> {
                if (v != null && v.contains("1")) return;
                if (web != null && web.canGoBack()) web.goBack();
                else MainActivity.super.onBackPressed();
            });
    }

    @Override protected void onResume() {
        super.onResume();
        h.post(syncWall);
        if (web != null) web.requestFocus();
        if (voice != null) voice.onActivityResume();
    }

    @Override protected void onPause() {
        h.removeCallbacks(syncWall);
        if (voice != null) voice.onActivityPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("FrigatePushBridge");
            web.removeJavascriptInterface("VoiceBridge");
            if (voice != null) { voice.shutdown(); voice = null; }
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
