package org.locationbridge.source;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.hardware.GeomagneticField;
import android.location.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class LocationService extends Service {
    static final String STOP = "org.locationbridge.source.STOP";
    static volatile LocationService instance;
    private static final int PORT = 18766;
    private static final int MAX_LINE = 16384;
    private final String session = UUID.randomUUID().toString();
    private final String[] names = {"fused", "gps", "network"};
    private final NativeLocationPolicy policy = new NativeLocationPolicy();
    private final NativeAltitude nativeAltitude = new NativeAltitude();
    private final GnssAltitude gnssAltitude = new GnssAltitude();
    private final DeadReckoning deadReckoning = new DeadReckoning();
    private final PositionPublication publication = new PositionPublication();
    private NativeMotionSensors motion;
    private JSONObject gnssMotion;
    private boolean inertialEnabled;
    private final Map<String, String> errors = new HashMap<>();
    private final Map<String, LocationListener> listeners = new HashMap<>();
    private final Map<String, Long> providerEpochs = new HashMap<>(), retryAfter = new HashMap<>();
    private final Map<String, Integer> retrySeconds = new HashMap<>();
    private final Map<String, Boolean> usable = new HashMap<>();
    private final Handler health = new Handler(Looper.getMainLooper());
    private final Runnable checkProviders = new Runnable() {
        @Override public void run() {
            if (!running) return;
            maintainProviders();
            health.postDelayed(this, 2000);
        }
    };
    private volatile boolean running;
    private volatile ServerSocket server;
    private AuthProtocol auth;
    private LocationManager manager;
    private long sequence;
    private volatile long startupDeadline;
    private volatile boolean activated;

    @Override public void onCreate() {
        super.onCreate(); Language.init(this);
        instance = this;
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        inertialEnabled = getSharedPreferences("native_inertial", MODE_PRIVATE).getBoolean("enabled", true);
        motion = new NativeMotionSensors(this, new NativeMotionSensors.Listener() {
            @Override public void onSample(long elapsed, double east, double north) {
                synchronized (LocationService.this) {
                    if (running && inertialEnabled)
                        deadReckoning.acceleration(elapsed,east,north,SystemClock.elapsedRealtimeNanos());
                }
            }
            @Override public void onInvalidated(String reason) {
                synchronized (LocationService.this) { deadReckoning.invalidate(reason,SystemClock.elapsedRealtimeNanos()); }
            }
        });
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        long deadline = intent == null ? 0 : intent.getLongExtra("bridge_deadline", 0);
        long now = SystemClock.elapsedRealtime();
        if (deadline != 0 && (deadline <= now || deadline - now > 9000)) {
            if (!running) stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        String token = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getString(MainActivity.TOKEN, null);
        if (!AuthProtocol.isHex64(token)) { stopSelf(startId); return START_NOT_STICKY; }
        auth = new AuthProtocol(token);
        startupDeadline = deadline;
        activated = deadline == 0; // A manual start is not a bridge launch.
        if (!activated) {
            long delay = Math.max(0, deadline - SystemClock.elapsedRealtime());
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                synchronized (LocationService.this) {
                    if (!activated && SystemClock.elapsedRealtime() >= startupDeadline) {
                        running = false;
                        stopSelf();
                    }
                }
            }, delay);
        }
        try {
            createNotification();
            running = true;
            if (inertialEnabled) motion.start();
            maintainProviders();
            health.postDelayed(checkProviders, 2000);
            ServerSocket listener = new ServerSocket();
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
            server = listener;
            new Thread(this::serve, "locationbridge-socket").start();
        } catch (Exception e) {
            synchronized (this) { errors.put("service", e.getClass().getSimpleName()); }
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    void refreshNotification() { createNotification(); }
    private void createNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("location", Language.tr("定位助手"), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, LocationService.class).setAction(STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "location")
                .setContentTitle(Language.tr("定位助手运行中")).setContentText(Language.tr("正在向 Termux 提供本机位置"))
                .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentIntent(open)
                .setOngoing(true).addAction(new Notification.Action.Builder(null, Language.tr("停止"), stopAction).build()).build();
        startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    }
    private String providerIssue(String name) {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "permission_missing";
        try {
            if (manager.getProvider(name) == null) return "provider_unavailable";
            return manager.isProviderEnabled(name) ? null : "provider_disabled";
        } catch (RuntimeException e) { return e.getClass().getSimpleName(); }
    }
    private void withdraw(String name, String reason, long now) {
        LocationListener listener=listeners.remove(name);
        if (listener != null || Boolean.TRUE.equals(usable.get(name))) {
            providerEpochs.put(name,providerEpochs.getOrDefault(name,0L)+1);
            policy.remove(name,now);
            nativeAltitude.remove(name);
            if ("gps".equals(name)) gnssAltitude.clear();
            if ("gps".equals(name)) { deadReckoning.invalidate(reason,SystemClock.elapsedRealtimeNanos()); gnssMotion=null; }
        }
        usable.put(name,false);errors.put(name,reason);
        if (listener != null) try { manager.removeUpdates(listener); } catch (RuntimeException ignored) { }
    }
    private synchronized void maintainProviders() {
        if (!running) return;
        long now=SystemClock.elapsedRealtimeNanos();
        for (String name : names) {
            String issue=providerIssue(name);
            if (issue != null) { withdraw(name,issue,now); continue; }
            if (Boolean.FALSE.equals(usable.get(name))) {
                policy.remove(name,now); // Re-enabled sources must provide a new post-enable fix.
                retryAfter.remove(name);retrySeconds.remove(name);
            }
            usable.put(name,true);
            if (!listeners.containsKey(name) && now>=retryAfter.getOrDefault(name,0L)) subscribe(name,now);
        }
    }
    private void subscribe(final String name, long now) {
        final long epoch=providerEpochs.getOrDefault(name,0L)+1;
        providerEpochs.put(name,epoch);
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { receive(name,location,epoch); }
            @Override public void onProviderDisabled(String provider) {
                synchronized (LocationService.this) {
                    if (running && providerEpochs.getOrDefault(name,0L)==epoch)
                        withdraw(name,"provider_disabled",SystemClock.elapsedRealtimeNanos());
                }
            }
        };
        try {
            LocationRequest request = new LocationRequest.Builder(1000L)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(500L)
                    .setMinUpdateDistanceMeters(0f)
                    .setMaxUpdateDelayMillis(0L)
                    .build();
            manager.requestLocationUpdates(name, request, getMainExecutor(), listener);
            listeners.put(name, listener);
            errors.remove(name);retryAfter.remove(name);retrySeconds.remove(name);
        } catch (RuntimeException e) {
            providerEpochs.put(name,epoch+1);
            try { manager.removeUpdates(listener); } catch (RuntimeException ignored) { }
            errors.put(name,e.getClass().getSimpleName());
            int seconds=Math.min(30,retrySeconds.getOrDefault(name,0)==0?2:retrySeconds.get(name)*2);
            retrySeconds.put(name,seconds);retryAfter.put(name,now+seconds*1_000_000_000L);
        }
    }
    private synchronized void receive(String name, Location loc, long epoch) {
        if (!running || providerEpochs.getOrDefault(name,0L)!=epoch || !listeners.containsKey(name)) return;
        long now = SystemClock.elapsedRealtimeNanos();
        String issue=providerIssue(name);
        if (issue != null) { withdraw(name,issue,now); return; }
        if (!loc.hasAccuracy()) {
            policy.remove(name,now);nativeAltitude.remove(name);
            if ("gps".equals(name)) gnssAltitude.clear();
            if ("gps".equals(name)) deadReckoning.invalidate("invalid_gnss",now);
            return;
        }
        Selector.Fix f = new Selector.Fix(name, loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(),
                loc.getElapsedRealtimeNanos(), loc.isMock(), loc.getTime(), sequence + 1);
        if (!policy.offer(f,now)) {
            String reason=policy.sourceReason(name,now);
            if ("jump".equals(reason) || "inaccurate".equals(reason) || "invalid".equals(reason)) {
                nativeAltitude.remove(name);
                if ("gps".equals(name)) gnssAltitude.clear();
            }
            if ("gps".equals(name) && !"out_of_order".equals(reason)) deadReckoning.invalidate("gnss_"+reason,now);
            return;
        }
        sequence++;
        nativeAltitude.update(name,loc.isMock(),loc.getElapsedRealtimeNanos(),now,
                loc.getLatitude(),loc.getLongitude(),loc.hasAltitude(),loc.getAltitude(),
                loc.hasVerticalAccuracy(),loc.getVerticalAccuracyMeters());
        if ("gps".equals(name)) {
            gnssAltitude.update(loc.getProvider(), loc.isMock(), loc.getElapsedRealtimeNanos(), now,
                    loc.getLatitude(), loc.getLongitude(), loc.hasAltitude(), loc.getAltitude(),
                    loc.hasVerticalAccuracy(), loc.getVerticalAccuracyMeters());
            double speed=loc.hasSpeed()?loc.getSpeed():Double.NaN;
            double speedAccuracy=loc.hasSpeedAccuracy()?loc.getSpeedAccuracyMetersPerSecond():Double.NaN;
            double bearing=loc.hasBearing()?loc.getBearing():Double.NaN;
            double bearingAccuracy=loc.hasBearingAccuracy()?loc.getBearingAccuracyDegrees():Double.NaN;
            boolean hasHeight=loc.hasAltitude() && Double.isFinite(loc.getAltitude());
            double declination=Double.NaN;
            try {
                double value=new GeomagneticField((float)f.latitude,(float)f.longitude,
                        hasHeight?(float)loc.getAltitude():0,f.timestamp).getDeclination();
                if (Double.isFinite(value) && Math.abs(value)<=180) declination=value;
            } catch (RuntimeException ignored) { }
            Double magneticBearing=MagneticCourse.fromTrue(bearing,declination);
            try {
                gnssMotion=new JSONObject().put("elapsed_realtime_nanos",f.elapsed)
                    .put("speed_m_s",finiteOrNull(speed)).put("speed_accuracy_m_s",finiteOrNull(speedAccuracy))
                    .put("bearing_deg",finiteOrNull(bearing)).put("bearing_accuracy_deg",finiteOrNull(bearingAccuracy))
                    .put("magnetic_bearing_deg",magneticBearing==null?JSONObject.NULL:magneticBearing)
                    .put("magnetic_declination_deg",finiteOrNull(declination))
                    .put("declination_source","android_geomagnetic_model")
                    .put("declination_altitude_assumed",!hasHeight);
            } catch (Exception ignored) { gnssMotion=null; }
            if (inertialEnabled) {
                motion.setReference(f.latitude,f.longitude,hasHeight?loc.getAltitude():0,f.timestamp,f.elapsed,hasHeight);
                if (!deadReckoning.anchor(f,speed,speedAccuracy,bearing,bearingAccuracy,now))
                    deadReckoning.invalidate("gnss_velocity_unavailable",now);
            }
        }
    }
    private static Object finiteOrNull(double value) { return Double.isFinite(value)?value:JSONObject.NULL; }
    synchronized void setInertialEnabled(boolean enabled) {
        getSharedPreferences("native_inertial", MODE_PRIVATE).edit().putBoolean("enabled",enabled).apply();
        inertialEnabled=enabled;
        deadReckoning.invalidate(enabled?"waiting_gnss":"disabled",SystemClock.elapsedRealtimeNanos());
        if (running && enabled) motion.start(); else motion.stop();
    }
    boolean isReady() { return running && server != null; }
    private JSONObject provider(String name) throws Exception {
        boolean available = false, enabled = false;
        String error;
        synchronized(this) { error = errors.get(name); }
        try {
            available = manager.getProvider(name) != null;
            if (available) enabled = manager.isProviderEnabled(name);
        } catch (RuntimeException e) { error = e.getClass().getSimpleName(); }
        return new JSONObject().put("available", available).put("enabled", enabled)
                .put("error", error == null ? JSONObject.NULL : error)
                .put("registered",listeners.containsKey(name)).put("sample_state",policy.sourceReason(name,SystemClock.elapsedRealtimeNanos()));
    }
    synchronized JSONObject snapshot() throws Exception {
        long now = SystemClock.elapsedRealtimeNanos();
        JSONObject providers = new JSONObject();
        for (String name : names) {
            String issue=providerIssue(name);
            if (running && issue != null) withdraw(name,issue,now);
            providers.put(name, provider(name));
        }
        Selector.Fix f = running ? policy.selected(now) : null;
        DeadReckoning.Prediction predicted=running && inertialEnabled?deadReckoning.predict(now):null;
        PositionPublication.Output output=publication.select(f,predicted,now);
        JSONObject result = new JSONObject().put("protocol", 1).put("session_id", session)
                .put("running", running).put("elapsed_realtime_nanos", now).put("providers", providers)
                .put("selection",new JSONObject().put("reason",running?policy.reason(now):"stopped")
                    .put("rejected_count",policy.rejectedCount()).put("switch_count",policy.switchCount()));
        Object sample = JSONObject.NULL;
        if (output != null) sample = positionJson(output);
        Object nativeSample=JSONObject.NULL;
        if (f != null) nativeSample=new JSONObject().put("sequence", f.sequence).put("source", f.source)
                .put("latitude", f.latitude).put("longitude", f.longitude).put("accuracy_m", f.accuracy)
                .put("elapsed_realtime_nanos", f.elapsed).put("timestamp_ms", f.timestamp)
                .put("is_mock", false).put("coordinate_system", "WGS84");
        GnssAltitude.Reading altitude = f != null ? gnssAltitude.fresh(now) : null;
        Object altitudeJson = JSONObject.NULL;
        if (altitude != null && altitude.hasAltitude) {
            altitudeJson = new JSONObject().put("source", "gps").put("datum", "WGS84_ellipsoid")
                    .put("altitude_m", altitude.altitude).put("elapsed_realtime_nanos", altitude.elapsed)
                    .put("vertical_accuracy_m", altitude.verticalAccuracy == null ? JSONObject.NULL : altitude.verticalAccuracy);
        }
        NativeAltitude.Reading height=running ? nativeAltitude.fresh(now,f) : null;
        Object heightJson=JSONObject.NULL;
        if (height != null) heightJson=new JSONObject().put("source",height.source).put("datum","WGS84_ellipsoid")
                .put("altitude_m",height.altitude).put("elapsed_realtime_nanos",height.elapsed)
                .put("vertical_accuracy_m",height.verticalAccuracy==null?JSONObject.NULL:height.verticalAccuracy);
        JSONObject inertial=new JSONObject().put("enabled",inertialEnabled)
                .put("reason",inertialEnabled?deadReckoning.reason(now):"disabled")
                .put("active",output!=null && output.prediction!=null)
                .put("sensors",motion.snapshot());
        Object velocity=gnssMotion;
        if (gnssMotion==null || now-gnssMotion.optLong("elapsed_realtime_nanos")>10_000_000_000L) velocity=JSONObject.NULL;
        return result.put("sample", sample).put("native_sample",nativeSample).put("gnss_motion",velocity)
                .put("inertial",inertial).put("gnss_altitude", altitudeJson).put("altitude",heightJson);
    }
    private JSONObject positionJson(PositionPublication.Output out) throws Exception {
        Selector.Fix f=out.measured;
        DeadReckoning.Prediction p=out.prediction;
        JSONObject sample=new JSONObject().put("sequence",out.sequence)
                .put("source",f!=null?f.source:"inertial")
                .put("latitude",f!=null?f.latitude:p.latitude).put("longitude",f!=null?f.longitude:p.longitude)
                .put("accuracy_m",f!=null?f.accuracy:p.uncertaintyMeters)
                .put("elapsed_realtime_nanos",out.elapsed)
                .put("timestamp_ms",f!=null?f.timestamp:p.timestampMillis)
                .put("is_mock",false).put("coordinate_system","WGS84");
        if (p!=null) sample.put("accuracy_kind","model_estimate").put("anchor_source","gps")
                .put("anchor_elapsed_realtime_nanos",p.anchorElapsed).put("prediction_age_ms",p.predictionAgeMillis);
        return sample;
    }
    private void serve() {
        while (running) {
            Socket accepted;
            try {
                ServerSocket listener = server;
                if (listener == null) break;
                accepted = listener.accept();
            } catch (IOException e) {
                if (running) new Handler(Looper.getMainLooper()).post(this::stopSelf);
                break;
            }
            try (Socket client = accepted) {
                long deadline = SystemClock.elapsedRealtime() + 2000;
                InputStream in = client.getInputStream();
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int b = -1;
                while (line.size() < MAX_LINE) {
                    long left = deadline - SystemClock.elapsedRealtime();
                    if (left <= 0) break;
                    client.setSoTimeout((int)left);
                    b = in.read();
                    if (b == -1 || b == '\n') break;
                    line.write(b);
                }
                if (b != '\n' || line.size() >= MAX_LINE) continue;
                JSONObject request = new JSONObject(line.toString("UTF-8"));
                Object nonceValue = request.opt("nonce"), opValue = request.opt("operation"), macValue = request.opt("mac");
                if (!(nonceValue instanceof String) || !(opValue instanceof String) || !(macValue instanceof String)) continue;
                String nonce = (String)nonceValue, command = (String)opValue;
                if (!auth.verify(nonce, command, (String)macValue)) continue;
                if (command.equals("activate")) {
                    synchronized (this) {
                        if (!running || (startupDeadline != 0 && SystemClock.elapsedRealtime() >= startupDeadline && !activated)) {
                            running = false;
                            new Handler(Looper.getMainLooper()).post(this::stopSelf);
                        } else {
                            activated = true;
                        }
                    }
                } else if (command.equals("stop")) {
                    running = false;
                }
                try {
                    String payload = (command.equals("external_snapshot") ? ExternalBleSnapshot.snapshot(this) : snapshot()).toString();
                    String mac = auth.sign("response\n" + nonce + "\n" + payload);
                    JSONObject reply = new JSONObject().put("nonce", nonce).put("payload", payload).put("mac", mac);
                    byte[] data = (reply.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                    if (data.length <= MAX_LINE && SystemClock.elapsedRealtime() < deadline) {
                        client.getOutputStream().write(data);
                        client.getOutputStream().flush();
                    }
                } finally {
                    if (command.equals("stop")) {
                        ServerSocket listener = server;
                        server = null;
                        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
                        new Handler(Looper.getMainLooper()).post(this::stopSelf);
                    }
                }
            } catch (Exception e) {
                if (!running || server == null) break;
            }
        }
    }
    @Override public void onDestroy() {
        running = false;
        health.removeCallbacksAndMessages(null);
        ServerSocket socket = server; server = null;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        for (LocationListener listener : listeners.values()) {
            try { manager.removeUpdates(listener); } catch (RuntimeException ignored) { }
        }
        listeners.clear();
        gnssAltitude.clear();
        nativeAltitude.clear();
        motion.stop();
        deadReckoning.invalidate("stopped",SystemClock.elapsedRealtimeNanos());
        if (instance == this) instance = null;
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
