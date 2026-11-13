package org.locationbridge.source;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssAutomaticGainControl;
import android.location.GnssCapabilities;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementRequest;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded diagnostics only; never produces or alters a position. */
final class NativeGnssMonitor {
    private final Context context;
    private final LocationManager manager;
    private final Object lock = new Object();
    private GnssDispatch executor;
    private final GnssRetry statusRetry = new GnssRetry(), measurementRetry = new GnssRetry();
    private String measurementTimeBasis;
    private GnssStatus.Callback statusCallback;
    private GnssMeasurementsEvent.Callback measurementCallback;
    private boolean running, requestedFull, statusRegistered, measurementRegistered, fullRequestAccepted;
    private Boolean measurementsCap, antennaCap;
    private Integer antennaCount;
    private long epoch, measurementTime;
    private int measurementCount, agcCount;
    private Double agcMin, agcMax;
    private Boolean reportedFull;
    private GnssQuality.Summary status;
    private String state = "stopped";

    NativeGnssMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    void start(boolean requestFullTracking) {
        synchronized (lock) {
            if (running && requestedFull == requestFullTracking) return;
            if (running) stopLocked();
            running = true; requestedFull = requestFullTracking;
            executor = new GnssDispatch(SystemClock::elapsedRealtimeNanos);
            refreshLocked();
        }
    }
    void refreshAvailability() { synchronized (lock) { if (running) refreshLocked(); } }

    private void unregisterLocked() {
        ++epoch;
        if (manager != null) {
            try { if (statusCallback != null) manager.unregisterGnssStatusCallback(statusCallback); } catch (RuntimeException ignored) { }
            try { if (measurementCallback != null) manager.unregisterGnssMeasurementsCallback(measurementCallback); } catch (RuntimeException ignored) { }
        }
        statusCallback = null; measurementCallback = null;
        statusRegistered = measurementRegistered = fullRequestAccepted = false;
        status = null; measurementTime = 0; measurementCount = agcCount = 0;
        agcMin = agcMax = null; reportedFull = null; measurementTimeBasis = null;
        statusRetry.succeeded(); measurementRetry.succeeded();
    }

    private void refreshLocked() {
        if (manager == null) { unregisterLocked(); state = "unsupported"; return; }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            unregisterLocked(); state = "permission_required"; return;
        }
        try {
            if (!manager.isLocationEnabled() || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                unregisterLocked(); state = "disabled"; return;
            }
        } catch (RuntimeException e) { unregisterLocked(); state = "error"; return; }
        try {
            GnssCapabilities capabilities = manager.getGnssCapabilities();
            measurementsCap = capabilities.hasMeasurements(); antennaCap = capabilities.hasAntennaInfo();
            java.util.List<android.location.GnssAntennaInfo> antennas = manager.getGnssAntennaInfos();
            antennaCount = antennas == null ? null : Math.min(antennas.size(), 256);
        } catch (RuntimeException e) { measurementsCap = antennaCap = null; antennaCount = null; }
        if (statusCallback == null) {
        final long generation = ++epoch;
        final GnssDispatch dispatch = executor;
        statusCallback = new GnssStatus.Callback() {
            @Override public void onSatelliteStatusChanged(GnssStatus event) {
                GnssQuality q = new GnssQuality();
                int n = Math.min(event.getSatelliteCount(), 1024);
                for (int i=0; i<n; i++) q.add(event.getConstellationType(i), event.getSvid(i), event.usedInFix(i),
                    event.getCn0DbHz(i), event.hasCarrierFrequencyHz(i) ? event.getCarrierFrequencyHz(i) : Double.NaN);
                long received = dispatch.receiptNanos();
                long now = SystemClock.elapsedRealtimeNanos();
                synchronized (lock) {
                    long previous = status == null ? 0 : status.elapsed;
                    if (running && epoch == generation && GnssQuality.validTime(received, now, previous)) {
                        status = q.finish(received); state = "active";
                    }
                }
            }
        };
        measurementCallback = new GnssMeasurementsEvent.Callback() {
            @Override public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
                int count = 0, ac = 0; Double min = null, max = null;
                if (Build.VERSION.SDK_INT >= 33) {
                    for (GnssAutomaticGainControl agc : event.getGnssAutomaticGainControls()) {
                        double v = agc.getLevelDb();
                        if (Double.isFinite(v)) { min=min==null?v:Math.min(min,v); max=max==null?v:Math.max(max,v); ac++; }
                        if (ac >= 1024) break;
                    }
                }
                boolean legacyAgc = ac == 0;
                for (GnssMeasurement m : event.getMeasurements()) {
                    count++;
                    if (legacyAgc && m.hasAutomaticGainControlLevelDb()) {
                        double v = m.getAutomaticGainControlLevelDb();
                        if (Double.isFinite(v)) { min=min==null?v:Math.min(min,v); max=max==null?v:Math.max(max,v); ac++; }
                    }
                    if (count >= 1024) break;
                }
                Boolean full = Build.VERSION.SDK_INT >= 34 && event.hasIsFullTracking() ? event.isFullTracking() : null;
                long received = dispatch.receiptNanos();
                boolean clockAvailable = event.getClock().hasElapsedRealtimeNanos();
                long timestamp = clockAvailable ? event.getClock().getElapsedRealtimeNanos() : received;
                long now = SystemClock.elapsedRealtimeNanos();
                synchronized (lock) {
                    if (!running || epoch != generation || timestamp > received || !GnssQuality.validTime(timestamp, now, measurementTime)) return;
                    measurementTime=timestamp; measurementTimeBasis=clockAvailable ? "gnss_clock" : "callback_receipt";
                    measurementCount=count; agcCount=ac; agcMin=min; agcMax=max; reportedFull=full; state="active";
                }
            }
        };
        }
        long now = SystemClock.elapsedRealtimeNanos();
        if (!statusRegistered && statusRetry.ready(now)) {
            try { statusRegistered = manager.registerGnssStatusCallback(executor, statusCallback); }
            catch (RuntimeException ignored) { statusRegistered = false; }
            if (statusRegistered) statusRetry.succeeded(); else statusRetry.failed(now);
        }
        if (!measurementRegistered && !Boolean.FALSE.equals(measurementsCap) && measurementRetry.ready(now)) {
            if (requestedFull) {
                try {
                    GnssMeasurementRequest request = new GnssMeasurementRequest.Builder().setFullTracking(true).build();
                    measurementRegistered = manager.registerGnssMeasurementsCallback(request, executor, measurementCallback);
                    fullRequestAccepted = measurementRegistered;
                } catch (RuntimeException ignored) { measurementRegistered = false; }
            }
            if (!measurementRegistered) {
                try {
                    GnssMeasurementRequest request = new GnssMeasurementRequest.Builder().setFullTracking(false).build();
                    measurementRegistered = manager.registerGnssMeasurementsCallback(request, executor, measurementCallback);
                } catch (RuntimeException ignored) { measurementRegistered = false; }
            }
            if (measurementRegistered) measurementRetry.succeeded(); else measurementRetry.failed(now);
        }
        if (status == null && measurementTime == 0) state = statusRegistered || measurementRegistered ? "waiting" : "error";
    }

    JSONObject snapshot(long now) throws JSONException {
        synchronized (lock) {
            boolean freshStatus = status != null && status.fresh(now);
            boolean freshMeasurement = measurementTime > 0 && now >= measurementTime && now - measurementTime <= GnssQuality.MAX_AGE_NANOS;
            JSONObject capabilities = new JSONObject().put("measurements", nullable(measurementsCap)).put("antenna_info", nullable(antennaCap))
                    .put("antenna_count", nullable(antennaCount));
            JSONObject result = new JSONObject().put("state", running && state.equals("active") && !freshStatus && !freshMeasurement ? "waiting" : state)
                    .put("capabilities", capabilities).put("status_registered", statusRegistered)
                    .put("measurements_registered", measurementRegistered).put("full_tracking_requested", requestedFull)
                    .put("full_tracking_request_accepted", fullRequestAccepted)
                    .put("status_elapsed_realtime_nanos", freshStatus ? status.elapsed : JSONObject.NULL)
                    .put("measurement_elapsed_realtime_nanos", freshMeasurement ? measurementTime : JSONObject.NULL)
                    .put("status_time_basis", freshStatus ? "callback_receipt" : JSONObject.NULL)
                    .put("measurement_time_basis", freshMeasurement ? nullable(measurementTimeBasis) : JSONObject.NULL);
            Object statusJson = JSONObject.NULL;
            if (freshStatus) statusJson = new JSONObject().put("satellites_visible",status.visible).put("satellites_used",status.used)
                    .put("signals",status.signals).put("cn0_count",status.cn0Count).put("cn0_mean_dbhz",nullable(status.cn0Mean))
                    .put("cn0_max_dbhz",nullable(status.cn0Max)).put("constellations",new JSONObject(status.constellations))
                    .put("bands",new JSONObject(status.bands));
            Object measurementJson = JSONObject.NULL;
            if (freshMeasurement) measurementJson = new JSONObject().put("count",measurementCount).put("agc_count",agcCount)
                    .put("agc_min_db",nullable(agcMin)).put("agc_max_db",nullable(agcMax)).put("full_tracking_reported",nullable(reportedFull));
            return result.put("status",statusJson).put("measurements",measurementJson);
        }
    }
    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
    void stop() { synchronized (lock) { stopLocked(); } }
    private void stopLocked() {
        running = false; unregisterLocked(); state = "stopped";
        if (executor != null) { executor.close(); executor = null; }
    }
}
