package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import com.termux.R;

/**
 * Servico em primeiro plano: segura um wake lock e mostra a notificacao enquanto o painel estiver no ar.
 * Encerra sozinho quando o painel some.
 */
public class MCHostKeepAlive extends Service {
    static final String CHANNEL = "mchost";
    static final int NOTIF_ID = 7001;

    private PowerManager.WakeLock wakeLock;
    private volatile boolean running;
    private boolean lastOnline = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        running = true;

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Servidor Minecraft", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIF_ID, buildNotification("Aguardando o painel iniciar..."));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mchost:server");
        wakeLock.acquire();

        new Thread(this::watch, "mchost-watch").start();
        return START_STICKY;
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MCHostActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.mchost_ic_stat)
                .setContentTitle("MCHost")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void watch() {
        boolean seenOnline = false;
        int fails = 0;
        long started = SystemClock.elapsedRealtime();
        while (running) {
            boolean ok = MCHostActivity.ping(panelUrl());
            if (ok) {
                seenOnline = true;
                fails = 0;
            } else {
                fails++;
                boolean lost = seenOnline && fails >= 6;
                boolean neverCame = !seenOnline && SystemClock.elapsedRealtime() - started > 120000;
                if (lost || neverCame) {
                    stopSelf();
                    return;
                }
            }
            if (ok != lastOnline) {
                lastOnline = ok;
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(NOTIF_ID, buildNotification(ok ? "Painel online - servidor ativo" : "Painel fora do ar"));
            }
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private String panelUrl() {
        SharedPreferences p = getSharedPreferences("mchost", MODE_PRIVATE);
        return p.getString("url", MCHostActivity.DEFAULT_URL);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
