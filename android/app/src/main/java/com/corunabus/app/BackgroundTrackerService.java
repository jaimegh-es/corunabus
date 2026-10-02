package com.corunabus.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BackgroundTrackerService extends Service implements LocationListener {
    private static final String TAG = "BgTrackerService";

    public static final String ACTION_START = "com.corunabus.app.action.START_TRACKING";
    public static final String ACTION_STOP = "com.corunabus.app.action.STOP_TRACKING";

    public static final String CHANNEL_BG_ID = "buscoruna_bg_live_v2";
    public static final String CHANNEL_ALERTS_ID = "buscoruna_alerts_channel";
    // Silent high-priority channel used when a configured melody (default mp3 or
    // custom upload) plays instead of the system notification sound, avoiding a
    // double sound.
    public static final String CHANNEL_ALERTS_SILENT_ID = "buscoruna_alerts_silent_ch";

    public static final int NOTIFICATION_ID_SERVICE = 424243;
    public static final int NOTIFICATION_ID_ARRIVAL_ALERT = 9001;
    public static final int NOTIFICATION_ID_GPS_ALERT = 9002;

    public static volatile boolean isRunning = false;

    private String busId = "";
    private String lineId = "";
    private int originStopId = 0;
    private String originStopName = "";
    private int destinationStopId = 0;
    private String destinationStopName = "";
    private double destLat = 0.0;
    private double destLon = 0.0;
    private double prevLat = 0.0;
    private double prevLon = 0.0;
    private int leadMinutes = 2;
    private int walkMinutes = 0;
    private String locationName = "";
    private boolean etaAlertEnabled = true;
    private boolean gpsAlertEnabled = true;
    private String lang = "es";
    // Unified tracking phase: "toStop" = waiting at the boarding stop (show the
    // time until the bus reaches the stop), "toDest" = on board (show the time
    // to the destination). Empty for legacy single-phase journeys.
    private String phase = "";
    // Configured alert melodies: base64 data URI when the user uploaded a custom
    // mp3 from Settings, empty string = use the bundled default mp3.
    private String busSound = "";
    private String stopSound = "";

    private boolean etaTriggered = false;
    private boolean gpsTriggered = false;
    private int lastEta = -1;
    private int lastDestEta = -1;
    // Guards the self-stop scheduled after arrival so it only fires once.
    private volatile boolean stopScheduled = false;

    // --- Journey guards ------------------------------------------------------
    // The func=0 feed lists buses by proximity, with no direction field and a
    // very generous range (it happily reports a bus 12 km away), so "the bus is
    // not in the feed" is not proof of arrival: a timeout, a 429, or a bus that
    // has not come near yet all look exactly the same. The old code finished
    // the journey on the very first such poll, which killed tracking about one
    // minute after the "press the stop button" alert, while the bus was still
    // minutes away. Finishing now requires positive proof.
    private static final int ARRIVAL_CONFIRM_POLLS = 2;    // two samples, 10s apart
    private static final int MISSING_POLLS_TO_FINISH = 6;  // a full minute of answered polls
    private static final int FAILED_POLLS_TO_FINISH = 18;  // 3 minutes with no data at all
    private static final long MAX_JOURNEY_MS = 90 * 60 * 1000L;
    private static final int ARRIVED_DISTANCE_M = 50;       // the bus is at the stop
    private static final int USER_ARRIVED_DISTANCE_M = 150; // the user is on it

    private int originArrivedPolls = 0;
    private int destArrivedPolls = 0;
    private int originMissingPolls = 0;
    private int destMissingPolls = 0;
    private int failedPolls = 0;
    private long journeyStartedAt = 0;
    // Whether this poll managed to read a usable feed at all, used for the
    // "no data for minutes" guard. Read data, not just a 200: a response saying
    // anything but OK counts as a failure too.
    private boolean pollGotData = false;
    // Sticky: the feed did report the bus standing at the stop at some point,
    // so from then on its absence from the feed means something.
    private boolean originSeenAtStop = false;
    private boolean destSeenAtStop = false;

    private ScheduledExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private LocationManager locationManager;
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannels();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CorunaBus:BgTrackerWakeLock");
            try {
                wakeLock.acquire(30 * 60 * 1000L); // 30 minutes max safety limit
            } catch (Exception e) {
                Log.w(TAG, "Could not acquire wake lock", e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            String newBusId = intent.getStringExtra("busId") != null ? intent.getStringExtra("busId") : "";
            int newOriginStopId = intent.getIntExtra("originStopId", 0);
            boolean isSameSession = isRunning && !busId.isEmpty() && busId.equalsIgnoreCase(newBusId) && originStopId == newOriginStopId;

            busId = newBusId;
            lineId = intent.getStringExtra("lineId") != null ? intent.getStringExtra("lineId") : "";
            originStopId = newOriginStopId;
            originStopName = intent.getStringExtra("originStopName") != null ? intent.getStringExtra("originStopName") : "";
            destinationStopId = intent.getIntExtra("destinationStopId", 0);
            destinationStopName = intent.getStringExtra("destinationStopName") != null ? intent.getStringExtra("destinationStopName") : "";
            destLat = intent.getDoubleExtra("destLat", 0.0);
            destLon = intent.getDoubleExtra("destLon", 0.0);
            prevLat = intent.getDoubleExtra("prevLat", 0.0);
            prevLon = intent.getDoubleExtra("prevLon", 0.0);
            leadMinutes = intent.getIntExtra("leadMinutes", 2);
            walkMinutes = intent.getIntExtra("walkMinutes", 0);
            locationName = intent.getStringExtra("locationName") != null ? intent.getStringExtra("locationName") : "";
            etaAlertEnabled = intent.getBooleanExtra("etaAlertEnabled", true);
            gpsAlertEnabled = intent.getBooleanExtra("gpsAlertEnabled", true);
            lang = intent.getStringExtra("lang") != null ? intent.getStringExtra("lang") : "es";
            phase = intent.getStringExtra("phase") != null ? intent.getStringExtra("phase") : "";
            // Melodies refresh on every start call (even mid-session), so a
            // change in Settings without restarting tracking takes effect.
            busSound = intent.getStringExtra("busSound") != null ? intent.getStringExtra("busSound") : "";
            stopSound = intent.getStringExtra("stopSound") != null ? intent.getStringExtra("stopSound") : "";

            if (!isSameSession) {
                etaTriggered = false;
                gpsTriggered = false;
                lastEta = -1;
                lastDestEta = -1;
                stopScheduled = false;
                originArrivedPolls = 0;
                destArrivedPolls = 0;
                originMissingPolls = 0;
                destMissingPolls = 0;
                failedPolls = 0;
                journeyStartedAt = System.currentTimeMillis();

                startForegroundServiceNotification();
                startPollingLoop();
                startLocationListener();
            } else {
                updateForegroundNotification();
            }
        }

        return START_STICKY;
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            // Channel 1: Background ongoing service notification (Default priority, visible on lockscreen)
            NotificationChannel bgChannel = new NotificationChannel(
                CHANNEL_BG_ID,
                "Coruña Bus · Seguimiento activo",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            bgChannel.setDescription("Seguimiento en tiempo real del autobús en pantalla de bloqueo y barra de notificaciones");
            bgChannel.setShowBadge(false);
            bgChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(bgChannel);

            // Channel 2: High priority alerts channel for arrival, departure and destination warnings
            NotificationChannel alertsChannel = new NotificationChannel(
                CHANNEL_ALERTS_ID,
                "Coruña Bus · Avisos de autobús",
                NotificationManager.IMPORTANCE_HIGH
            );
            alertsChannel.setDescription("Avisos para salir de casa/trabajo, llegada de autobús y aviso de parada de destino");
            alertsChannel.enableVibration(true);
            alertsChannel.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 400});
            alertsChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            alertsChannel.setShowBadge(true);
            notificationManager.createNotificationChannel(alertsChannel);

            // Silent channel: used when a configured melody is played instead of
            // the system sound (avoids double sound on arrival/get-off alerts).
            NotificationChannel silentAlertsChannel = new NotificationChannel(
                CHANNEL_ALERTS_SILENT_ID,
                "Coruña Bus · Avisos (melodía configurada)",
                NotificationManager.IMPORTANCE_HIGH
            );
            silentAlertsChannel.setDescription("Avisos con melodía configurada (sin sonido del sistema)");
            silentAlertsChannel.setSound(null, null);
            silentAlertsChannel.enableVibration(true);
            silentAlertsChannel.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 400});
            silentAlertsChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            silentAlertsChannel.setShowBadge(true);
            notificationManager.createNotificationChannel(silentAlertsChannel);
        }
    }

    private void startForegroundServiceNotification() {
        Notification notification = buildForegroundNotification();

        boolean hasFineLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarseLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (Build.VERSION.SDK_INT >= 34) { // Android 14+
            int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (hasFineLoc || hasCoarseLoc) {
                serviceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            try {
                startForeground(NOTIFICATION_ID_SERVICE, notification, serviceTypes);
            } catch (Exception e) {
                Log.e(TAG, "Failed startForeground with types, falling back", e);
                startForeground(NOTIFICATION_ID_SERVICE, notification);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
            int serviceTypes = (hasFineLoc || hasCoarseLoc) ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
            try {
                if (serviceTypes != 0) {
                    startForeground(NOTIFICATION_ID_SERVICE, notification, serviceTypes);
                } else {
                    startForeground(NOTIFICATION_ID_SERVICE, notification);
                }
            } catch (Exception e) {
                startForeground(NOTIFICATION_ID_SERVICE, notification);
            }
        } else {
            startForeground(NOTIFICATION_ID_SERVICE, notification);
        }
    }

    private Notification buildForegroundNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pOpenIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Intent stopIntent = new Intent(this, BackgroundTrackerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pStopIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        String origInfo = !originStopName.isEmpty() ? originStopName : ("Parada " + originStopId);
        String destInfo = !destinationStopName.isEmpty() ? destinationStopName : ("Parada " + destinationStopId);

        String title;
        String body;

        boolean waitingAtStop = "toStop".equals(phase);

        if (waitingAtStop) {
            // Phase 1: waiting for the bus at the boarding stop. Always show the
            // time remaining until the bus reaches the STOP (even when the
            // destination ETA is already known too — the user is still on the
            // platform and needs to know when to board).
            if (lastEta >= 0) {
                long etaTimeMs = System.currentTimeMillis() + lastEta * 60000L;
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
                String etaClock = sdf.format(new java.util.Date(etaTimeMs));
                String timeLabel = lastEta == 0
                    ? ("en".equals(lang) ? "Arriving now!" : "¡Llegando ahora!")
                    : (lastEta + " min (" + etaClock + ")");
                title = "🚌 Bus " + busId + " · " + timeLabel;
                body = ("en".equals(lang) ? "Waiting at: " : "Esperando en: ") + origInfo
                    + " → " + ("en".equals(lang) ? "Dest: " : "Destino: ") + destInfo;
            } else {
                title = "en".equals(lang)
                    ? ("🚌 Bus " + busId + " · Looking for the bus…")
                    : ("🚌 Bus " + busId + " · Buscando bus…");
                body = ("en".equals(lang) ? "Waiting at: " : "Esperando en: ") + origInfo
                    + " → " + ("en".equals(lang) ? "Dest: " : "Destino: ") + destInfo;
            }
        } else if (destinationStopId > 0 && destinationStopId != originStopId) {
            // Mode: Journey to destination stop (phase 'toDest' or legacy)
            if (lastDestEta >= 0) {
                long etaTimeMs = System.currentTimeMillis() + lastDestEta * 60000L;
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
                String etaClock = sdf.format(new java.util.Date(etaTimeMs));
                String timeLabel = lastDestEta == 0
                    ? ("en".equals(lang) ? "Arriving at destination!" : "¡Llegando a destino!")
                    : (lastDestEta + " min (" + etaClock + ")");
                title = "🚌 Bus " + busId + " → Destino · " + timeLabel;
                body = origInfo + " → " + destInfo + " (Quedan ~" + lastDestEta + " min a destino)";
            } else if (lastEta >= 0) {
                long etaTimeMs = System.currentTimeMillis() + lastEta * 60000L;
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
                String etaClock = sdf.format(new java.util.Date(etaTimeMs));
                title = "🚌 Bus " + busId + " · En origen en " + lastEta + " min (" + etaClock + ")";
                body = "Esperando en: " + origInfo + " → Destino: " + destInfo;
            } else {
                title = "en".equals(lang) ? ("🚌 Bus " + busId + " → Destination") : ("🚌 Bus " + busId + " → " + destInfo);
                body = origInfo + " → " + destInfo;
            }
        } else if (lastEta >= 0) {
            // Mode: Waiting for bus at origin stop
            long etaTimeMs = System.currentTimeMillis() + lastEta * 60000L;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
            String etaClock = sdf.format(new java.util.Date(etaTimeMs));
            String timeLabel = lastEta == 0 ? ("en".equals(lang) ? "Arriving now" : "¡Llegando ahora!") : (lastEta + " min (" + etaClock + ")");
            title = "🚌 Bus " + busId + " · " + timeLabel;
            if (walkMinutes > 0 && !locationName.isEmpty()) {
                body = "Parada: " + origInfo + " · Tardas " + walkMinutes + " min desde " + locationName;
            } else {
                body = ("en".equals(lang) ? "Waiting at: " : "Esperando en: ") + origInfo;
            }
        } else {
            title = "en".equals(lang) ? ("🚌 Bus " + busId + " · Tracking active") : ("🚌 Bus " + busId + " · Seguimiento activo");
            if (walkMinutes > 0 && !locationName.isEmpty()) {
                body = "Parada: " + origInfo + " · Tardas " + walkMinutes + " min desde " + locationName;
            } else {
                body = ("en".equals(lang) ? "Waiting at: " : "Esperando en: ") + origInfo;
            }
        }

        String stopLabel = "en".equals(lang) ? "Stop" : "Detener";

        return new NotificationCompat.Builder(this, CHANNEL_BG_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setSubText("Coruña Bus")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pOpenIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, stopLabel, pStopIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    private void updateForegroundNotification() {
        if (notificationManager != null && isRunning) {
            notificationManager.notify(NOTIFICATION_ID_SERVICE, buildForegroundNotification());
        }
    }

    private void startPollingLoop() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(() -> {
            try {
                pollEta();
            } catch (Exception e) {
                Log.e(TAG, "Error during pollEta", e);
            }
        }, 0, 10, TimeUnit.SECONDS);
    }

    private String fetchUrl(String urlString) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 CorunaBusNative/1.0");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");

            int code = conn.getResponseCode();
            if (code == 200) {
                reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "Fetch failed for " + urlString + ": " + e.getMessage());
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private boolean matchesLine(Object jsonLineVal, String targetLine) {
        if (jsonLineVal == null || targetLine == null) return false;
        String lStr = jsonLineVal.toString().trim();
        String tStr = targetLine.trim();
        if (lStr.equalsIgnoreCase(tStr)) return true;
        try {
            int lNum = Integer.parseInt(lStr);
            int tNum = Integer.parseInt(tStr);
            if (lNum == tNum) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private boolean matchesBus(Object jsonBusVal, String targetBus) {
        if (jsonBusVal == null || targetBus == null) return false;
        return jsonBusVal.toString().trim().equalsIgnoreCase(targetBus.trim());
    }

    private int parseWaitTime(String tiempoStr) {
        if (tiempoStr == null) return -1;
        tiempoStr = tiempoStr.trim();
        if (tiempoStr.equals(">>>") || tiempoStr.equalsIgnoreCase("PRX") || tiempoStr.equalsIgnoreCase("LLEGANDO")) {
            return 0;
        }
        try {
            return Integer.parseInt(tiempoStr);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Distance in metres reported by the func=0 feed, or -1 when absent or
     * unparseable. The feed reports it as a plain string in metres, so a
     * distance is a far better "is it really there?" signal than the ETA
     * alone: it does not jitter between 0 and 1 while the bus is still
     * hundreds of metres away.
     * Distancia en metros que da el feed func=0, o -1 si no está. Es una señal
     * mucho mejor que el ETA para saber si el bus está de verdad en la parada:
     * el ETA salta entre 0 y 1 con el bus aún a cientos de metros.
     */
    private int parseDistanceMeters(JSONObject busObj) {
        if (busObj == null) return -1;
        Object raw = busObj.opt("distancia");
        if (raw == null) return -1;
        try {
            return (int) Math.round(Double.parseDouble(raw.toString().trim()));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * True when the feed positively reports the bus standing at the stop:
     * within ARRIVED_DISTANCE_M, or an ETA of 0 as a fallback for feeds that
     * omit the distance.
     * Verdadero cuando el feed informa que el bus está en la parada: dentro de
     * 50 m, o con ETA 0 si el feed no manda distancia.
     */
    private boolean busIsAtStop(JSONObject busObj, int waitTime) {
        int meters = parseDistanceMeters(busObj);
        if (meters >= 0) return meters <= ARRIVED_DISTANCE_M;
        return waitTime == 0;
    }

    /**
     * Hard limits that no feed can talk the service out of: a journey that has
     * been open for 90 minutes, and one that has been unable to read anything
     * at all for 3 minutes (no data means no alert can ever fire, so polling
     * only drains the battery). Returns true when the journey is over and the
     * caller should stop working.
     * Topes que ningún feed puede esquivar: 90 minutos de viaje abierto, o 3
     * minutos sin poder leer nada (sin datos no puede sonar ningún aviso, así
     * que seguir sondeando solo gasta batería). true = el viaje ha acabado.
     */
    private boolean checkJourneyGuards() {
        if (journeyStartedAt > 0 && System.currentTimeMillis() - journeyStartedAt > MAX_JOURNEY_MS) {
            Log.i(TAG, "Journey time limit reached, stopping");
            scheduleStopAfter(10);
            return true;
        }
        return false;
    }

    /**
     * Renew the wake lock for as long as the journey lasts. It is acquired once
     * in onCreate with a 30 minute timeout, so without this a journey that
     * legitimately runs longer than half an hour would be silently starved of
     * CPU and "stop tracking" on its own.
     * Renueva el wake lock mientras dure el viaje. Se adquiere una vez en
     * onCreate con 30 minutos de límite, así que sin esto un viaje largo se
     * queda sin CPU y para él solo.
     */
    private void renewWakeLock() {
        if (wakeLock == null || wakeLock.isHeld()) return;
        try {
            wakeLock.acquire(MAX_JOURNEY_MS);
        } catch (Exception e) {
            Log.w(TAG, "Could not renew wake lock", e);
        }
    }

    /**
     * Confirmation that the user actually got there, a minute after the
     * destination alert fired. That alert can legitimately fire from the
     * previous stop, which is still a walk away, so it must never end the
     * journey by itself -- but if the user is still standing on the destination
     * a minute later, the trip is over.
     * Confirma que el usuario ha llegado, un minuto después del aviso de
     * destino. Ese aviso puede saltar legítimamente desde la parada anterior,
     * todavía a un paseo, así que no puede acabar el viaje él solo; pero si un
     * minuto después el usuario sigue en la parada, el viaje ha acabado.
     */
    private void scheduleUserArrivalCheck(long seconds) {
        if (executor == null || executor.isShutdown()) return;
        executor.schedule(() -> {
            if (!isRunning || !gpsTriggered || locationManager == null) return;
            try {
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (last == null) return; // no fix: better keep tracking than stop wrongly
                float[] res = new float[1];
                Location.distanceBetween(last.getLatitude(), last.getLongitude(), destLat, destLon, res);
                if (res[0] <= USER_ARRIVED_DISTANCE_M) {
                    Log.i(TAG, "User is at the destination, stopping");
                    scheduleStopAfter(20);
                }
            } catch (SecurityException e) {
                // Location permission was revoked: no way to confirm arrival.
                // Permiso retirado: no hay forma de confirmar la llegada.
                Log.w(TAG, "No permission to read the last known location", e);
            }
        }, seconds, TimeUnit.SECONDS);
    }

    /**
     * Stop the service a bit after arrival so the last notification/foreground
     * state is visible, without keeping the battery drain running forever.
     * Guarded so only one schedule wins.
     */
    private void scheduleStopAfter(long seconds) {
        if (stopScheduled || executor == null || executor.isShutdown()) return;
        stopScheduled = true;
        executor.schedule(() -> {
            if (isRunning) stopSelf();
        }, seconds, TimeUnit.SECONDS);
    }

    private void pollEta() {
        if (checkJourneyGuards()) return;
        renewWakeLock();
        if (originStopId <= 0) return;
        pollGotData = false;

        String json = fetchUrl("https://itranvias.com/queryitr_v3.php?func=0&dato=" + originStopId);
        if (json == null) {
            json = fetchUrl("https://xn--coruabus-g3a.inled.es/api/proxy?func=0&dato=" + originStopId);
        }

        if (json != null) {
            try {
                JSONObject obj = new JSONObject(json);
                if ("OK".equalsIgnoreCase(obj.optString("resultado"))) {
                    pollGotData = true;
                    JSONObject buses = obj.optJSONObject("buses");
                    if (buses != null) {
                        JSONArray lineas = buses.optJSONArray("lineas");
                        if (lineas != null) {
                            boolean originFound = false;
                            for (int i = 0; i < lineas.length(); i++) {
                                JSONObject lineaObj = lineas.getJSONObject(i);
                                Object lineVal = lineaObj.opt("linea");
                                if (!matchesLine(lineVal, lineId)) continue;

                                JSONArray busArray = lineaObj.optJSONArray("buses");
                                if (busArray == null) continue;

                                for (int j = 0; j < busArray.length(); j++) {
                                    JSONObject bObj = busArray.getJSONObject(j);
                                    Object bVal = bObj.opt("bus");
                                    if (matchesBus(bVal, busId)) {
                                        originFound = true;
                                        String tStr = bObj.optString("tiempo", "");
                                        int waitTime = parseWaitTime(tStr);
                                        if (waitTime >= 0) {
                                            lastEta = waitTime;
                                            updateForegroundNotification();

                                            if (etaAlertEnabled && !etaTriggered) {
                                                int alertThreshold = walkMinutes > 0 ? walkMinutes : leadMinutes;
                                                if (waitTime <= alertThreshold) {
                                                    etaTriggered = true;
                                                    if (walkMinutes > 0 && !locationName.isEmpty()) {
                                                        fireWalkDepartureAlert(waitTime, walkMinutes, locationName);
                                                    } else {
                                                        fireArrivalAlert(waitTime);
                                                    }
                                                }
                                            }

                                            // "Alert only" journeys (origin ==
                                            // destination) finish automatically
                                            // once the bus is really standing at
                                            // the stop, confirmed on two
                                            // consecutive answered polls so a
                                            // single odd sample cannot end the
                                            // journey early.
                                            // Modo "solo aviso": termina solo
                                            // cuando el bus está de verdad en la
                                            // parada, confirmado en dos sonadas
                                            // seguidas para que una muestra suelta
                                            // no lo corte antes de tiempo.
                                            if (destinationStopId == originStopId) {
                                                if (busIsAtStop(bObj, waitTime)) {
                                                    originSeenAtStop = true;
                                                    if (++originArrivedPolls >= ARRIVAL_CONFIRM_POLLS) {
                                                        scheduleStopAfter(45);
                                                    }
                                                } else {
                                                    originArrivedPolls = 0;
                                                }
                                            }
                                        }
                                        break;
                                    }
                                }
                            }
                            // Backstop for "alert only" journeys: the bus was
                            // reported at the stop and is no longer listed at
                            // all, so it arrived and went away (or the feed
                            // dropped it). Only a streak of *answered* polls
                            // counts -- a failed fetch proves nothing, and
                            // before the alert fires the bus is legitimately
                            // absent because it has not come near yet.
                            // Red de seguridad del modo "solo aviso": el bus se
                            // informó en la parada y ya no aparece, así que
                            // llegó y se marchó. Solo cuentan sonadas que
                            // respondieron: un fallo de red no prueba nada, y
                            // antes del aviso el bus aún no se ha acercado.
                            if (destinationStopId == originStopId) {
                                if (originFound) {
                                    originMissingPolls = 0;
                                } else if (originSeenAtStop && ++originMissingPolls >= MISSING_POLLS_TO_FINISH) {
                                    scheduleStopAfter(30);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error parsing origin ETA JSON", e);
            }
        }

        // Also check destination stop ETA if destination is set and distinct from origin
        if (destinationStopId > 0 && destinationStopId != originStopId) {
            String destJson = fetchUrl("https://itranvias.com/queryitr_v3.php?func=0&dato=" + destinationStopId);
            if (destJson == null) {
                destJson = fetchUrl("https://xn--coruabus-g3a.inled.es/api/proxy?func=0&dato=" + destinationStopId);
            }
            if (destJson != null) {
                try {
                    JSONObject dObj = new JSONObject(destJson);
                    if ("OK".equalsIgnoreCase(dObj.optString("resultado"))) {
                        pollGotData = true;
                        JSONObject dBuses = dObj.optJSONObject("buses");
                        if (dBuses != null) {
                            JSONArray dLineas = dBuses.optJSONArray("lineas");
                            if (dLineas != null) {
                                boolean destFound = false;
                                for (int i = 0; i < dLineas.length(); i++) {
                                    JSONObject dLineaObj = dLineas.getJSONObject(i);
                                    if (!matchesLine(dLineaObj.opt("linea"), lineId)) continue;
                                    JSONArray dBusArray = dLineaObj.optJSONArray("buses");
                                    if (dBusArray == null) continue;
                                    for (int j = 0; j < dBusArray.length(); j++) {
                                        JSONObject db = dBusArray.getJSONObject(j);
                                        if (matchesBus(db.opt("bus"), busId)) {
                                            int dTime = parseWaitTime(db.optString("tiempo", ""));
                                            if (dTime >= 0) {
                                                destFound = true;
                                                lastDestEta = dTime;
                                                updateForegroundNotification();
                                                if (dTime <= 2 && !gpsTriggered) {
                                                    gpsTriggered = true;
                                                    fireDestinationAlert();
                                                }
                                                // The bus is standing at the
                                                // destination stop: the journey is
                                                // over. Confirmed twice, so one
                                                // bad sample cannot end it early.
                                                // El bus está en la parada de
                                                // destino: viaje terminado. Se
                                                // confirma dos veces para que una
                                                // muestra suelta no lo corte.
                                                if (busIsAtStop(db, dTime)) {
                                                    destSeenAtStop = true;
                                                    if (++destArrivedPolls >= ARRIVAL_CONFIRM_POLLS) {
                                                        scheduleStopAfter(45);
                                                    }
                                                } else {
                                                    destArrivedPolls = 0;
                                                }
                                            }
                                            break;
                                        }
                                    }
                                }
                                // Backstop: the bus was reported at the
                                // destination and is no longer listed at all
                                // (it reached the terminus and the feed dropped
                                // it, or it came and went): finish instead of
                                // polling forever. Notice what is NOT a reason
                                // to stop: the alert having fired. That used to
                                // be enough, so a single poll that did not list
                                // the bus while it was still minutes away killed
                                // the journey.
                                // Red de seguridad: el bus se informó en
                                // destino y ya no aparece (llegó a cabecera, o
                                // llegó y se marchó). Lo que YA NO es motivo
                                // para parar: que haya saltado el aviso, que
                                // antes bastaba y cortaba el viaje con el bus
                                // aún a minutos.
                                if (destFound) {
                                    destMissingPolls = 0;
                                } else if (destSeenAtStop && ++destMissingPolls >= MISSING_POLLS_TO_FINISH) {
                                    scheduleStopAfter(30);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error checking dest ETA", e);
                }
            }
        }

        // Last resort: minutes of nothing but failed reads. Without a single
        // usable feed no alert can ever fire, so from here on the service only
        // costs battery.
        // Último recurso: minutos sin leer nada. Sin un solo feed utilizable no
        // puede sonar ningún aviso, así que a partir de aquí el servicio solo
        // gasta batería.
        if (pollGotData) {
            failedPolls = 0;
        } else if (++failedPolls >= FAILED_POLLS_TO_FINISH) {
            Log.i(TAG, "No usable data in " + failedPolls + " polls, stopping");
            scheduleStopAfter(15);
        }
    }

    /**
     * Play the configured melody for an alert.
     * `soundConfig` is either a base64 data URI (custom upload) or empty (use
     * the bundled default mp3, copied by `npx cap sync` from /public into
     * android assets). Returns false when nothing could be played, so the
     * caller falls back to the system notification sound.
     * Reproduce la melodía configurada: data URI base64 personalizada o el mp3
     * por defecto incluido en los assets. false si no se pudo reproducir.
     */
    private boolean playMelody(String soundConfig, String assetPath) {
        try {
            MediaPlayer player = new MediaPlayer();
            if (soundConfig != null && !soundConfig.isEmpty()) {
                // Custom base64 data URI (data:audio/mpeg;base64,....)
                String b64 = soundConfig;
                int comma = b64.indexOf(',');
                if (comma != -1) b64 = b64.substring(comma + 1);
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                if (data.length == 0 || data.length > 5 * 1024 * 1024) return false;
                String fileName = assetPath.contains("avisoparada")
                    ? "corunabus_stop_alert.mp3" : "corunabus_bus_alert.mp3";
                File f = new File(getCacheDir(), fileName);
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    fos.write(data);
                } finally {
                    fos.close();
                }
                player.setDataSource(f.getAbsolutePath());
            } else {
                player.setDataSource("file:///android_asset/" + assetPath);
            }
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(MediaPlayer::release);
            player.setOnErrorListener((mp, what, extra) -> { mp.release(); return true; });
            player.prepareAsync();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not play melody " + assetPath, e);
            return false;
        }
    }

    private void fireWalkDepartureAlert(int minutesLeft, int walkMins, String locName) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pOpenIntent = PendingIntent.getActivity(
            this, 4, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        String title = "en".equals(lang) ? "⏰ Time to leave for the bus stop!" : "⏰ ¡Hora de salir hacia la parada!";
        String stopName = !originStopName.isEmpty() ? originStopName : ("Parada " + originStopId);
        String body = "en".equals(lang)
            ? ("Bus " + busId + " arrives at " + stopName + " in " + minutesLeft + " min. It takes " + walkMins + " min from " + locName + ". Leave now!")
            : ("El bus " + busId + " llega a " + stopName + " en " + minutesLeft + " min. Tardas " + walkMins + " min desde " + locName + ". ¡Sal ahora!");

        boolean melody = playMelody(busSound, "public/avisobus.mp3");
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, melody ? CHANNEL_ALERTS_SILENT_ID : CHANNEL_ALERTS_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pOpenIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(new long[]{0, 500, 250, 500, 250, 500});
        if (!melody) builder.setDefaults(NotificationCompat.DEFAULT_ALL);

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID_ARRIVAL_ALERT, builder.build());
        }
        vibrateDevice();
    }

    private void fireArrivalAlert(int minutesLeft) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pOpenIntent = PendingIntent.getActivity(
            this, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        String title = "en".equals(lang) ? "🚌 Your bus is arriving!" : "🚌 ¡Tu autobús está llegando!";
        String stopName = !originStopName.isEmpty() ? originStopName : ("Parada " + originStopId);
        String body = "en".equals(lang)
            ? ("Bus " + busId + " arrives at " + stopName + " in " + minutesLeft + " min. Board the bus!")
            : ("El bus " + busId + " llega a " + stopName + " en " + minutesLeft + " min. ¡Sube al bus!");

        boolean melody = playMelody(busSound, "public/avisobus.mp3");
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, melody ? CHANNEL_ALERTS_SILENT_ID : CHANNEL_ALERTS_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pOpenIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(new long[]{0, 400, 200, 400, 200, 400});
        if (!melody) builder.setDefaults(NotificationCompat.DEFAULT_ALL);

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID_ARRIVAL_ALERT, builder.build());
        }
        vibrateDevice();
    }

    private void fireDestinationAlert() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pOpenIntent = PendingIntent.getActivity(
            this, 3, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        String title = "en".equals(lang) ? "⚠️ PRESS THE STOP BUTTON!" : "⚠️ ¡PULSA EL BOTÓN DE PARADA!";
        String destName = !destinationStopName.isEmpty() ? destinationStopName : ("Parada " + destinationStopId);
        String body = "en".equals(lang)
            ? ("Approaching your destination: " + destName + ". Alert the driver to get off!")
            : ("Te aproximas a tu destino: " + destName + ". ¡Avisa al conductor para bajarte!");

        boolean melody = playMelody(stopSound, "public/avisoparada.mp3");
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, melody ? CHANNEL_ALERTS_SILENT_ID : CHANNEL_ALERTS_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pOpenIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVibrate(new long[]{0, 500, 200, 500, 200, 500});
        if (!melody) builder.setDefaults(NotificationCompat.DEFAULT_ALL);

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID_GPS_ALERT, builder.build());
        }
        vibrateDevice();

        // The user has been told to press the stop button: the journey is over
        // from their point of view. Stop the whole service 1 minute later on
        // its own — which dismisses the persistent tracking notification —
        // instead of waiting for the arrival feed or for the user to open the
        // app to confirm arrival.
        // El usuario ya fue avisado de pulsar el botón de parada: el viaje
        // termina para él. El servicio se detiene solo 1 minuto después (con
        // lo que desaparece la notificación persistente de seguimiento), sin
        // esperar al feed de llegada ni a que el usuario abra la app.
        //
        // Only once on board: while still waiting at the boarding stop
        // ('toStop') this alert can be a false positive (short routes where
        // the boarding stop sits next to the destination, or the destination
        // feed already at ≤2 min before the user boards), and auto-ending
        // then would kill the journey before it really started. Legacy
        // journeys without phase are treated as on board.
        // Solo una vez a bordo: mientras se espera en la parada ('toStop')
        // este aviso puede ser falso (rutas cortas cuya parada de origen está
        // junto al destino, o el feed de destino a ≤2 min antes de subir) y
        // acabar entonces mataría el viaje antes de empezar. Los viajes
        // antiguos sin fase se tratan como a bordo.
        if (!"toStop".equals(phase)) {
            scheduleStopAfter(60);
        }
    }

    private void vibrateDevice() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 200, 400, 200, 400}, -1));
                } else {
                    v.vibrate(new long[]{0, 400, 200, 400, 200, 400}, -1);
                }
            }
        } catch (Exception ignored) {}
    }

    private void startLocationListener() {
        if (!gpsAlertEnabled || destLat == 0.0 || destLon == 0.0 || originStopId == destinationStopId) {
            return;
        }

        try {
            boolean hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

            if (!hasFine && !hasCoarse) return;

            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) return;

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 5, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 5, this);
            }
        } catch (SecurityException se) {
            Log.w(TAG, "Location permission missing", se);
        } catch (Exception e) {
            Log.w(TAG, "Error starting location updates", e);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null || gpsTriggered || destLat == 0.0 || destLon == 0.0) return;

        float[] results = new float[1];
        Location.distanceBetween(location.getLatitude(), location.getLongitude(), destLat, destLon, results);
        float distToDest = results[0];

        boolean atPrev = false;
        boolean passedPrev = false;
        if (prevLat != 0.0 && prevLon != 0.0) {
            float[] prevResults = new float[1];
            Location.distanceBetween(location.getLatitude(), location.getLongitude(), prevLat, prevLon, prevResults);
            float distToPrev = prevResults[0];

            float[] prevToDestResults = new float[1];
            Location.distanceBetween(prevLat, prevLon, destLat, destLon, prevToDestResults);
            float distPrevToDest = Math.max(80f, prevToDestResults[0]);

            // Spacing-aware thresholds: with very close stops the alert must not
            // fire too early or from the wrong stop. It only fires at the
            // second-to-last stop, or once the user is clearly closer to the
            // destination than to that stop.
            // Umbrales acordes a la distancia real entre paradas.
            atPrev = distToPrev < Math.min(100f, distPrevToDest * 0.45f);
            passedPrev = (distToDest < distToPrev) && (distToDest < Math.max(70f, distPrevToDest * 0.5f));
        }

        // Only when the previous stop could not be resolved, fall back to a
        // plain proximity check.
        boolean approachingDest = (prevLat == 0.0 && prevLon == 0.0) && distToDest < 150;

        if (atPrev || passedPrev || approachingDest) {
            gpsTriggered = true;
            fireDestinationAlert();
            // This alert can fire from the previous stop, which is still a walk
            // away, so it cannot end the journey on its own. Re-check shortly
            // instead: if the user is then standing on the destination, they
            // are there and the trip is over.
            // Este aviso puede saltar desde la parada anterior, todavía a un
            // paseo, así que no puede acabar el viaje él solo. Se vuelve a
            // comprobar: si el usuario está en la parada de destino, el viaje
            // ha terminado.
            scheduleUserArrivalCheck(90);
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    @Override
    public void onDestroy() {
        isRunning = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {}
            locationManager = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
            wakeLock = null;
        }
        stopForeground(true);
        // Tracking over: dismiss the "press the stop button" notification too,
        // so nothing is left waiting for the user in the tray.
        // Seguimiento terminado: también se cierra la notificación de "pulsa
        // el botón de parada" para que no quede nada esperando al usuario.
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID_GPS_ALERT);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
