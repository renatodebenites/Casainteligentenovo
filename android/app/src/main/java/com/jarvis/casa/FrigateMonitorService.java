package com.jarvis.casa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashSet;

/**
 * Serviço em primeiro plano: consulta o Frigate (eventos) e o Home Assistant (estado do alarme)
 * e dispara notificações mesmo com o app fechado.
 */
public class FrigateMonitorService extends Service {
    static final String CH_FG = "fp_fg";
    static final String CH_STD = "fp_std";
    static final String CH_STRONG = "fp_strong";
    static final int FG_ID = 1001;
    static final long POLL_MS = 4000;

    static volatile boolean running = false;

    private SharedPreferences p;
    private volatile boolean stopFlag = false;
    private Thread th;
    private PowerManager.WakeLock wl;
    private long startTs;
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        p = getSharedPreferences("fp", Context.MODE_PRIVATE);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // intent == null => o sistema reiniciou o serviço depois de matá-lo
        if (intent == null) {
            if (!p.getBoolean("bg", false)) { stopSelf(); return START_NOT_STICKY; }
            p.edit().putInt("restarts", p.getInt("restarts", 0) + 1).apply();
        }
        Notification n = foregroundNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FG_ID, n);
        }
        running = true;
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:monitor");
                wl.setReferenceCounted(false);
                wl.acquire();
            }
        }
        if (th == null || !th.isAlive()) {
            stopFlag = false;
            startTs = System.currentTimeMillis() / 1000;
            th = new Thread(this::loop, "jarvis-monitor");
            th.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopFlag = true;
        running = false;
        if (th != null) th.interrupt();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- loop
    private void loop() {
        while (!stopFlag) {
            try { poll(); } catch (Throwable ignored) { }
            try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
        }
    }

    private static String trimUrl(String s) {
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }

    private void poll() throws Exception {
        String url = trimUrl(p.getString("url", ""));
        String token = p.getString("token", "");
        String frig = trimUrl(p.getString("frigate", ""));
        String alarm = p.getString("alarm", "");
        boolean ala = p.getBoolean("ala", true);
        boolean ok = false;

        // 1) Estado do alarme (liga/desliga os alertas automaticamente)
        if (ala && !url.isEmpty() && !token.isEmpty() && !alarm.isEmpty()) {
            String s = httpText(url + "/api/states/" + alarm, token, null);
            if (s != null) {
                ok = true;
                String st = new JSONObject(s).optString("state", "");
                if (!st.isEmpty() && !st.equals("unavailable") && !st.equals("unknown")
                        && !st.equals("disarming") && !st.equals(p.getString("last", ""))) {
                    boolean armed = st.startsWith("armed") || st.equals("arming")
                            || st.equals("pending") || st.equals("triggered");
                    SharedPreferences.Editor ed = p.edit().putString("last", st).putBoolean("gate", armed);
                    // IA por zonas do Frigate só no "Armado ausente (IA + alarme)"
                    // Também liga se o usuário pediu "ausente" e a central armou em outro estado
                    boolean wantAway = "away".equals(p.getString("armreq", ""));
                    boolean recent = System.currentTimeMillis() - p.getLong("armts", 0) < 45000;
                    if (st.equals("disarmed") && wantAway && recent) {
                        // passagem intermediária da central ao trocar de modo: mantém o pedido de ausente
                    } else {
                        if (st.equals("armed_away") || (st.startsWith("armed") && wantAway)) ed.putBoolean("ia", true);
                        else if (st.startsWith("armed") || st.equals("disarmed")) ed.putBoolean("ia", false);
                        if (st.equals("disarmed")) ed.putString("armreq", "");
                    }
                    ed.apply();
                }
            }
        }

        // 2) Eventos do Frigate
        if (!frig.isEmpty()) {
            long after = System.currentTimeMillis() / 1000 - 120;
            String s = httpText(frig + "/api/events?limit=30&after=" + after, null, null);
            if (s != null) {
                ok = true;
                handleEvents(new JSONArray(s), frig, url, token);
            }
        }
        if (ok) p.edit().putLong("lastOk", System.currentTimeMillis()).apply();
    }

    private void handleEvents(JSONArray arr, String frig, String haUrl, String token) {
        boolean gate = p.getBoolean("gate", true);
        boolean iaOk = !p.getBoolean("ala", true) || p.getBoolean("ia", true);
        boolean noDet = p.getBoolean("noDetect", false);
        String siren = p.getString("siren", "");

        for (int i = arr.length() - 1; i >= 0; i--) { // Frigate devolve do mais novo para o mais antigo
            JSONObject e = arr.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id", "");
            if (id.isEmpty()) continue;
            if (e.optDouble("start_time", 0) < startTs - 5) continue;

            JSONArray zones = e.optJSONArray("zones");
            if ((zones == null || zones.length() == 0) && e.optJSONObject("data") != null)
                zones = e.optJSONObject("data").optJSONArray("zones");
            boolean hasZone = zones != null && zones.length() > 0;

            if (noDet && !hasZone) continue; // só avisa quando uma zona é invadida
            String key = hasZone ? id + "#z" : id;
            if (seen.contains(key)) continue;
            seen.add(key);
            while (seen.size() > 300) { seen.remove(seen.iterator().next()); }
            if (!gate) continue; // alarme desarmado: alertas pausados
            if (hasZone && !iaOk) continue; // zona do Frigate só com IA ativa (armado ausente)

            String cam = e.optString("camera", "câmera");
            String label = e.optString("label", "objeto");
            StringBuilder zs = new StringBuilder();
            if (hasZone) {
                for (int z = 0; z < zones.length(); z++) {
                    if (z > 0) zs.append(", ");
                    zs.append(zones.optString(z).replace('_', ' '));
                }
            }
            String title = hasZone ? "Zona invadida · " + cam.replace('_', ' ')
                                   : "Detecção · " + cam.replace('_', ' ');
            String text = label + (hasZone ? " na zona " + zs : " detectado");
            notifyEvent(key, id, cam, label, title, text, frig);

            if (hasZone && !siren.isEmpty() && !haUrl.isEmpty() && !token.isEmpty()) {
                try {
                    JSONObject body = new JSONObject().put("entity_id", siren);
                    httpText(haUrl + "/api/services/homeassistant/turn_on", token, body.toString());
                } catch (Exception ignored) { }
            }
        }
    }

    // ------------------------------------------------------- notificações
    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel fg = new NotificationChannel(CH_FG, "Monitoramento ativo",
                NotificationManager.IMPORTANCE_LOW);
        fg.setShowBadge(false);
        nm.createNotificationChannel(fg);

        NotificationChannel std = new NotificationChannel(CH_STD, "Alertas de segurança",
                NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(std);

        NotificationChannel strong = new NotificationChannel(CH_STRONG, "Alertas fortes (alarme)",
                NotificationManager.IMPORTANCE_HIGH);
        strong.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        strong.enableVibration(true);
        strong.setVibrationPattern(new long[]{0, 600, 300, 600, 300, 600});
        strong.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(strong);
    }

    private Notification.Builder builder(String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, channel)
                                           : new Notification.Builder(this);
    }

    private PendingIntent openApp(int req, String nevJson) {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (nevJson != null) i.putExtra("nev", nevJson);
        return PendingIntent.getActivity(this, req, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification foregroundNotification() {
        return builder(CH_FG)
                .setSmallIcon(R.drawable.ic_stat_jarvis)
                .setContentTitle("Jarvis · Casa Inteligente")
                .setContentText("Monitorando câmeras e alarme")
                .setOngoing(true)
                .setContentIntent(openApp(0, null))
                .build();
    }

    private void notifyEvent(String key, String id, String cam, String label,
                             String title, String text, String frig) {
        try {
            boolean strong = p.getBoolean("strong", false);
            JSONObject nev = new JSONObject().put("id", id).put("camera", cam).put("label", label);
            int nid = 2000 + (key.hashCode() & 0xFFFFF);

            Notification.Builder b = builder(strong ? CH_STRONG : CH_STD)
                    .setSmallIcon(R.drawable.ic_stat_jarvis)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setContentIntent(openApp(nid, nev.toString()));
            if (Build.VERSION.SDK_INT < 26) {
                b.setPriority(Notification.PRIORITY_HIGH);
                if (strong) b.setDefaults(Notification.DEFAULT_ALL);
            }

            byte[] img = httpBytes(frig + "/api/events/" + id + "/thumbnail.jpg");
            if (img != null) {
                Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, img.length);
                if (bmp != null) b.setStyle(new Notification.BigPictureStyle().bigPicture(bmp));
            }
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(nid, b.build());
        } catch (Exception ignored) { }
    }

    // --------------------------------------------------------------- HTTP
    /** GET (ou POST se body != null). Retorna null em falha. */
    private String httpText(String u, String token, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            if (c.getResponseCode() / 100 != 2) return null;
            return new String(readAll(c.getInputStream()), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private byte[] httpBytes(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(4000);
            if (c.getResponseCode() / 100 != 2) return null;
            return readAll(c.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
