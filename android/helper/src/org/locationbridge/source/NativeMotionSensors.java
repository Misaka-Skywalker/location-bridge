package org.locationbridge.source;

import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;

/** Reports true-east/north linear acceleration; does not infer course or velocity. */
final class NativeMotionSensors implements SensorEventListener {
    interface Listener {
        void onSample(long elapsedNanos, double eastMps2, double northMps2);
        void onInvalidated(String reason);
    }
    private static final long SECOND = 1_000_000_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SensorManager manager;
    private final Sensor rotation, acceleration;
    private final Listener listener;
    private final Object lock = new Object();
    private boolean started, rotationRegistered, accelerationRegistered, referenceValid, referenceAltitudeAssumed;
    private long rotationTime, referenceTime, samples, rejected;
    private int rotationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE, accelerationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private String state = "stopped", headingAccuracy = "unknown";
    private double declination;
    private final MotionOrientationHistory orientations = new MotionOrientationHistory();
    private final MotionAccelerationGate accelerationGate = new MotionAccelerationGate();
    private long lifecycle, activeLifecycle;
    private boolean desiredRunning;
    private long referenceGeneration;

    NativeMotionSensors(Context context, Listener listener) {
        if (context == null || listener == null) throw new IllegalArgumentException("context and listener required");
        this.listener = listener;
        manager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        rotation = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        acceleration = manager == null ? null : manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
    }
    void start() {
        final long generation;
        synchronized(lock) { if (desiredRunning) return; desiredRunning=true; generation=++lifecycle; }
        runMain(() -> {
            boolean replace;
            synchronized(lock) { if (!desiredRunning || lifecycle!=generation) return; replace=started && activeLifecycle!=generation; }
            if (replace) stopMain();
            synchronized(lock) { if (!desiredRunning || lifecycle!=generation) return; activeLifecycle=generation; }
            startMain();
        });
    }
    void stop() {
        final long generation;
        synchronized(lock) { desiredRunning=false; generation=++lifecycle; }
        runMain(() -> {
            synchronized(lock) { if (desiredRunning || lifecycle!=generation) return; }
            stopMain();
        });
    }
    void setReference(double lat, double lon, double altitudeMeters, long utcMillis, long elapsedNanos, boolean altitudeKnown) {
        final long generation;
        synchronized(lock) { if (!desiredRunning) return; generation=lifecycle; }
        runMain(() -> {
            synchronized(lock) {
                if (!desiredRunning || lifecycle!=generation || activeLifecycle!=generation
                        || !started || !rotationRegistered || !accelerationRegistered) return;
            }
            referenceMain(lat,lon,altitudeMeters,utcMillis,elapsedNanos,altitudeKnown,generation);
        });
    }
    private void runMain(Runnable task) { if (Looper.myLooper() == Looper.getMainLooper()) task.run(); else main.post(task); }
    private void invalidate(String reason) {
        synchronized(lock) { state=reason; rejected++; }
        listener.onInvalidated(reason);
    }
    private void startMain() {
        synchronized(lock) { if (started) return; started=true; state="starting"; }
        if (manager == null || rotation == null || acceleration == null) { invalidate("sensor_unavailable"); return; }
        boolean r = false, a = false;
        try {
            r = manager.registerListener(this,rotation,50_000,0,main);
            a = manager.registerListener(this,acceleration,50_000,0,main);
        } catch (RuntimeException ignored) { }
        synchronized(lock) { rotationRegistered=r; accelerationRegistered=a; }
        if (!r || !a) { manager.unregisterListener(this); synchronized(lock) { rotationRegistered=false; accelerationRegistered=false; } invalidate("registration_failed"); }
        else synchronized(lock) { state="waiting_reference"; }
    }
    private void stopMain() {
        if (manager != null) manager.unregisterListener(this);
        synchronized(lock) {
            started=false; rotationRegistered=false; accelerationRegistered=false; referenceValid=false;
            referenceGeneration++; rotationTime=0; accelerationGate.clear(); referenceTime=0; orientations.clear();
            rotationAccuracy=SensorManager.SENSOR_STATUS_UNRELIABLE; accelerationAccuracy=SensorManager.SENSOR_STATUS_UNRELIABLE;
            headingAccuracy="unknown"; state="stopped";
        }
        listener.onInvalidated("stopped");
    }
    private void referenceMain(double lat,double lon,double altitude,long utc,long elapsed,boolean altitudeKnown,long lifecycleToken) {
        long now=SystemClock.elapsedRealtimeNanos();
        if (!Double.isFinite(lat) || Math.abs(lat)>90 || !Double.isFinite(lon) || Math.abs(lon)>180
                || !Double.isFinite(altitude) || utc<=0 || elapsed<=0 || elapsed>now || now-elapsed>1_500_000_000L) {
            synchronized(lock) { if (!desiredRunning || lifecycle!=lifecycleToken || activeLifecycle!=lifecycleToken) return; referenceValid=false; referenceGeneration++; }
            invalidate("invalid_reference"); return;
        }
        double actualAltitude=altitudeKnown ? altitude : 0;
        double d=new GeomagneticField((float)lat,(float)lon,(float)actualAltitude,utc).getDeclination();
        if (!Double.isFinite(d)) { synchronized(lock) { if (!desiredRunning || lifecycle!=lifecycleToken || activeLifecycle!=lifecycleToken) return; referenceValid=false; referenceGeneration++; } invalidate("invalid_reference"); return; }
        final long generation;
        synchronized(lock) {
            if (!desiredRunning || lifecycle!=lifecycleToken || activeLifecycle!=lifecycleToken || !started || !rotationRegistered || !accelerationRegistered) return;
            if (referenceValid && elapsed<=referenceTime) return;
            declination=d; referenceTime=elapsed; referenceValid=true; referenceAltitudeAssumed=!altitudeKnown;
            generation=++referenceGeneration;
        }
        long remaining = Math.max(1, (10*SECOND-(now-elapsed)+999_999)/1_000_000);
        main.postDelayed(() -> {
            boolean expired;
            synchronized(lock) {
                expired=desiredRunning && lifecycle==lifecycleToken && activeLifecycle==lifecycleToken && started && referenceValid && generation==referenceGeneration && SystemClock.elapsedRealtimeNanos()-referenceTime>=10*SECOND;
                if (expired) referenceValid=false;
            }
            if (expired) invalidate("reference_expired");
        },remaining);
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {
        if (Looper.myLooper()!=Looper.getMainLooper()) { main.post(() -> onAccuracyChanged(sensor,accuracy)); return; }
        boolean active;
        synchronized(lock) {
            active=started;
            if (sensor==rotation) rotationAccuracy=accuracy;
            else if (sensor==acceleration) accelerationAccuracy=accuracy;
            else return;
            if (sensor==rotation && accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) { orientations.clear(); }
        }
        if (active && accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) invalidate("sensor_accuracy_low");
    }
    @Override public void onSensorChanged(SensorEvent event) {
        // Registration specifies the main Handler; listeners must remain serialized here.
        if (Looper.myLooper()!=Looper.getMainLooper()) { main.post(() -> invalidate("wrong_thread")); return; }
        synchronized(lock) { if (!desiredRunning || lifecycle!=activeLifecycle || !started || !rotationRegistered || !accelerationRegistered) return; }
        long now=SystemClock.elapsedRealtimeNanos(), t=event.timestamp;
        if (t<=0 || t>now || event.accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            if (event.sensor==rotation) synchronized(lock) { orientations.clear(); }
            invalidate("invalid_sensor_sample"); return;
        }
        if (event.sensor==rotation) {
            if (event.values == null || event.values.length<3) { synchronized(lock) { orientations.clear(); } invalidate("invalid_rotation"); return; }
            for (float v:event.values) if (!Float.isFinite(v)) { synchronized(lock) { orientations.clear(); } invalidate("invalid_rotation"); return; }
            float h=event.values.length>4 ? event.values[4] : -1;
            if (h < 0 && h != -1 || h > Math.toRadians(20)) { synchronized(lock) { orientations.clear(); headingAccuracy="invalid"; } invalidate("heading_accuracy_low"); return; }
            float[] next=new float[9];
            try { SensorManager.getRotationMatrixFromVector(next,event.values); }
            catch (RuntimeException ex) { synchronized(lock) { orientations.clear(); } invalidate("invalid_rotation"); return; }
            synchronized(lock) {
                if (t<=rotationTime) { orientations.clear(); next=null; }
                else { rotationTime=t; rotationAccuracy=event.accuracy; orientations.add(t,next); headingAccuracy=h<0?"unknown":"known"; }
            }
            if (next==null) invalidate("rotation_out_of_order");
            return;
        }
        if (event.sensor!=acceleration) return;
        float[] current; double d; String issue=null;
        synchronized(lock) {
            String decision=accelerationGate.observe(t,referenceTime,now,referenceValid);
            if ("ignore".equals(decision)) return;
            if (!"accept".equals(decision)) issue=decision;
            else accelerationAccuracy=event.accuracy;
            current=orientations.at(t);
            if (issue==null && (current==null || rotationAccuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)) issue="direction_unavailable";
            d=declination;
        }
        if (issue!=null) { invalidate(issue); return; }
        try {
            double[] horizontal=MotionTransform.horizontal(current,event.values,d);
            synchronized(lock) { samples++; state="active"; }
            listener.onSample(t,horizontal[0],horizontal[1]);
        } catch (IllegalArgumentException ex) { invalidate("invalid_acceleration"); }
    }
    JSONObject snapshot() {
        synchronized(lock) {
            try { return new JSONObject().put("state",state).put("started",started)
                    .put("rotation_available",rotation!=null).put("acceleration_available",acceleration!=null)
                    .put("rotation_registered",rotationRegistered).put("acceleration_registered",accelerationRegistered)
                    .put("rotation_accuracy",rotationAccuracy).put("acceleration_accuracy",accelerationAccuracy)
                    .put("heading_accuracy",headingAccuracy).put("reference_valid",referenceValid)
                    .put("reference_altitude_assumed",referenceValid && referenceAltitudeAssumed)
                    .put("samples",samples).put("rejected",rejected); }
            catch (Exception impossible) { return new JSONObject(); }
        }
    }
}
