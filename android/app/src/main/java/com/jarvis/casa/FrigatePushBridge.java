package com.jarvis.casa;

import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Ponte JS <-> Android usada pelo index.html (window.FrigatePushBridge). */
public class FrigatePushBridge {
    private final MainActivity a;
    private final SharedPreferences p;

    FrigatePushBridge(MainActivity a) {
        this.a = a;
        this.p = a.getSharedPreferences("fp", Context.MODE_PRIVATE);
    }

    private void toast(final String m) {
        a.runOnUiThread(() -> Toast.makeText(a, m, Toast.LENGTH_LONG).show());
    }

    @JavascriptInterface
    public void start(String json) {
        try {
            JSONObject o = new JSONObject(json);
            p.edit()
                .putString("url", o.optString("url", ""))
                .putString("token", o.optString("token", ""))
                .putString("frigate", o.optString("frigate", ""))
                .putString("alarm", o.optString("alarm", ""))
                .putString("siren", o.optString("siren", ""))
                .putBoolean("noDetect", o.optBoolean("noDetect", false))
                .putBoolean("bg", true)
                .apply();
        } catch (Exception ignored) { }
        Intent i = new Intent(a, FrigateMonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i);
            else a.startService(i);
        } catch (Exception e) {
            toast("Não foi possível iniciar o monitoramento: " + e.getMessage());
        }
    }

    @JavascriptInterface
    public void stop() {
        p.edit().putBoolean("bg", false).apply();
        a.stopService(new Intent(a, FrigateMonitorService.class));
    }

    @JavascriptInterface
    public void battery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
    }

    @JavascriptInterface
    public boolean batteryOk() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(a.getPackageName());
    }

    @JavascriptInterface
    public String monitorInfo() {
        try {
            JSONObject o = new JSONObject();
            o.put("brand", Build.MANUFACTURER);
            o.put("lastOk", p.getLong("lastOk", 0));
            o.put("notif", notifEnabled());
            o.put("restarts", p.getInt("restarts", 0));
            o.put("running", FrigateMonitorService.running);
            o.put("strong", p.getBoolean("strong", false));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean notifEnabled() {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    @JavascriptInterface
    public String alertsState() {
        try {
            JSONObject o = new JSONObject();
            o.put("gate", p.getBoolean("gate", true));
            o.put("ia", p.getBoolean("ia", true));
            o.put("last", p.getString("last", ""));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface public void setAlerts(boolean v) { p.edit().putBoolean("gate", v).apply(); }
    @JavascriptInterface public void setIa(boolean v) { p.edit().putBoolean("ia", v).apply(); }
    @JavascriptInterface public void setArmReq(String v) { p.edit().putString("armreq", v == null ? "" : v).putLong("armts", System.currentTimeMillis()).apply(); }
    @JavascriptInterface public void setAla(boolean v) { p.edit().putBoolean("ala", v).apply(); }
    @JavascriptInterface public void setStrong(boolean v) {
        p.edit().putBoolean("strong", v).apply();
        toast(v ? "Alerta forte ativado" : "Alerta forte desativado");
    }

    @JavascriptInterface
    public void setBars(final String hex) {
        a.runOnUiThread(() -> a.setBars(hex));
    }

    @JavascriptInterface
    public void wallMode(final boolean on) {
        a.runOnUiThread(() -> a.setWall(on));
    }

    @JavascriptInterface
    public boolean isTv() {
        try {
            android.app.UiModeManager um = (android.app.UiModeManager) a.getSystemService(Context.UI_MODE_SERVICE);
            if (um != null && um.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return true;
            android.content.pm.PackageManager pm = a.getPackageManager();
            return pm.hasSystemFeature("android.software.leanback")
                    || !pm.hasSystemFeature("android.hardware.touchscreen");
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void notifPerm() {
        a.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 33) {
                a.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        MainActivity.REQ_NOTIF);
            } else {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
                try { a.startActivity(i); } catch (Exception ignored) { }
            }
        });
    }

    @JavascriptInterface
    public void autostart() {
        String[][] oem = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
            {"com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"},
        };
        for (String[] c : oem) {
            try {
                Intent i = new Intent().setComponent(new ComponentName(c[0], c[1]))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
                return;
            } catch (Exception ignored) { }
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void testFrigate(final String base) {
        new Thread(() -> {
            String b = base == null ? "" : base.trim().replaceAll("/+$", "");
            if (b.isEmpty()) { toast("Frigate não configurado."); return; }
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(b + "/api/version").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                int code = c.getResponseCode();
                toast(code == 200 ? "Frigate acessível ✔" : "Frigate respondeu HTTP " + code);
                c.disconnect();
            } catch (Exception e) {
                toast("Sem acesso ao Frigate: " + e.getMessage());
            }
        }).start();
    }

    @JavascriptInterface
    public void download(String url, String fn) {
        enqueueDownload(a, url, fn);
    }

    static void enqueueDownload(Context ctx, String url, String fn) {
        try {
            String name = safeName(fn);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(ctx, "Baixando " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(ctx, "Falha no download: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String safeName(String fn) {
        if (fn == null || fn.trim().isEmpty()) fn = "arquivo";
        return fn.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    @JavascriptInterface
    public void savePdf(final String b64, final String fn) {
        new Thread(() -> {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                String name = safeName(fn);
                if (!name.toLowerCase().endsWith(".pdf")) name += ".pdf";
                OutputStream out;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = a.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    out = a.getContentResolver().openOutputStream(u);
                } else {
                    File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    d.mkdirs();
                    out = new FileOutputStream(new File(d, name));
                }
                out.write(bytes);
                out.close();
                toast("PDF salvo em Downloads: " + name);
            } catch (Exception e) {
                toast("Não foi possível salvar o PDF: " + e.getMessage());
            }
        }).start();
    }
}
