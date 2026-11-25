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
        void onSample(long elapsedNanos, double eastMps2, double northMps2,
                      double magnitudeMps2, double angularSpeedRadPerSecond);
        void onInvalidated(String reason);
    }
    private static final long SECOND = 1_000_000_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SensorManager manager;
    private final Sensor standardRotation, geomagneticRotation, linearAcceleration, rawAcceleration, gravity, magnetometer, gyroscope, pressure;
    private Sensor rotation, acceleration;
    private boolean degradedRotation, gravityFallback, gravityRegistered, magneticRegistered, gyroRegistered, pressureRegistered;
    private float[] gravityValues, magneticValues;
    private long gravityTime, magneticTime, pressureTime, sessionStartNanos;
    private int gravityAccuracy, magneticAccuracy;
    private double pressureHpa=Double.NaN;
    private final Listener listener;
    private final Object lock = new Object();
    private boolean started, rotationRegistered, accelerationRegistered, referenceValid, referenceAltitudeAssumed;
    private long rotationTime, referenceTime, samples, rejected;
    private int rotationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE, accelerationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private String state = "stopped", headingAccuracy = "unknown";
    private double declination;
    private final MotionOrientationHistory orientations = new MotionOrientationHistory();
    private final MotionAccelerationGate accelerationGate = new MotionAccelerationGate();
    private final SensorRetryState retry = new SensorRetryState();
    private long retryToken;
    private long lifecycle, activeLifecycle;
    private boolean desiredRunning;

    NativeMotionSensors(Context context, Listener listener) {
        if (context == null || listener == null) throw new IllegalArgumentException("context and listener required");
        this.listener = listener;
        manager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        standardRotation = sensor(Sensor.TYPE_ROTATION_VECTOR);
        geomagneticRotation = sensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        linearAcceleration = sensor(Sensor.TYPE_LINEAR_ACCELERATION);
        rawAcceleration = sensor(Sensor.TYPE_ACCELEROMETER);
        gravity = sensor(Sensor.TYPE_GRAVITY);
        magnetometer = sensor(Sensor.TYPE_MAGNETIC_FIELD);
        gyroscope = sensor(Sensor.TYPE_GYROSCOPE);
        pressure = sensor(Sensor.TYPE_PRESSURE);
    }
    private Sensor sensor(int type) { return manager==null?null:manager.getDefaultSensor(type); }
    private boolean register(Sensor sensor) { return register(sensor,50_000); }
    private boolean register(Sensor sensor,int periodMicros) { try { return sensor!=null && manager.registerListener(this,sensor,periodMicros,0,main); } catch(RuntimeException error) { return false; } }
    void start() {
        final long generation;
        synchronized(lock) { if (desiredRunning) return; desiredRunning=true; generation=++lifecycle; retryToken=retry.start(); }
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
        synchronized(lock) { desiredRunning=false; generation=++lifecycle; retry.stop(); }
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
        final long generation, token;
        synchronized(lock) {
            if(started || !desiredRunning || activeLifecycle!=lifecycle) return;
            generation=lifecycle; token=retryToken;
            started=true;state="starting";sessionStartNanos=SystemClock.elapsedRealtimeNanos();
        }
        boolean r=false, a=false, useGeomagnetic=false, useGravity=false, mag=false, grav=false, gyro=false, press=false;
        Sensor selectedRotation=null, selectedAcceleration=null;
        if(manager!=null) {
            r=register(standardRotation);
            if(r) selectedRotation=standardRotation;
            if(!r && geomagneticRotation!=null && magnetometer!=null) {
                mag=register(magnetometer);
                boolean g=mag && register(geomagneticRotation);
                if(g) { selectedRotation=geomagneticRotation;useGeomagnetic=true;r=true; }
                else if(mag) {manager.unregisterListener(this,magnetometer);mag=false;}
            }
            a=register(linearAcceleration);
            if(a) selectedAcceleration=linearAcceleration;
            if(!a && rawAcceleration!=null && gravity!=null) {
                grav=register(gravity);
                boolean ar=grav && register(rawAcceleration);
                if(ar) {selectedAcceleration=rawAcceleration;useGravity=true;a=true;}
                else if(grav) {manager.unregisterListener(this,gravity);grav=false;}
            }
            if(r && a) {
                gyro=register(gyroscope,20_000); // Optional alignment of attitude to the acceleration timestamp.
                press=register(pressure);
            }
        }
        boolean current;
        synchronized(lock) { current=desiredRunning && lifecycle==generation && activeLifecycle==generation && retry.valid(token); }
        if(!current) { if(manager!=null) manager.unregisterListener(this); return; }
        if(!r || !a) {
            if(manager!=null) manager.unregisterListener(this);
            long delay;
            synchronized(lock) {
                if(!desiredRunning || lifecycle!=generation || !retry.valid(token)) return;
                started=false;rotation=null;acceleration=null;rotationRegistered=false;accelerationRegistered=false;
                gravityRegistered=false;magneticRegistered=false;gyroRegistered=false;pressureRegistered=false;
                degradedRotation=false;gravityFallback=false;sessionStartNanos=0;
                delay=retry.fail(token);
            }
            invalidate("sensor_unavailable");
            if(delay>0) main.postDelayed(()->{
                synchronized(lock) { if(!desiredRunning || lifecycle!=generation || activeLifecycle!=generation || !retry.valid(token)) return; }
                startMain();
            },delay);
            return;
        }
        synchronized(lock) {
            if(!desiredRunning || lifecycle!=generation || !retry.valid(token)) {manager.unregisterListener(this);return;}
            rotation=selectedRotation;acceleration=selectedAcceleration;
            degradedRotation=useGeomagnetic;gravityFallback=useGravity;
            magneticRegistered=mag;gravityRegistered=grav;gyroRegistered=gyro;pressureRegistered=press;
            rotationRegistered=true;accelerationRegistered=true;state="waiting_reference";
            retry.success(token);
        }
    }
    private void stopMain() {
        if (manager != null) manager.unregisterListener(this);
        synchronized(lock) {
            started=false; rotationRegistered=false; accelerationRegistered=false; referenceValid=false;
            rotationTime=0; accelerationGate.clear(); referenceTime=0; orientations.clear();
            rotationAccuracy=SensorManager.SENSOR_STATUS_UNRELIABLE; accelerationAccuracy=SensorManager.SENSOR_STATUS_UNRELIABLE;
            headingAccuracy="unknown"; state="stopped";
            gravityValues=null; magneticValues=null; gravityTime=magneticTime=pressureTime=0;
            gravityAccuracy=magneticAccuracy=SensorManager.SENSOR_STATUS_UNRELIABLE;
            pressureHpa=Double.NaN; gravityRegistered=false; magneticRegistered=false; gyroRegistered=false; pressureRegistered=false;
            degradedRotation=false;gravityFallback=false;rotation=null;acceleration=null;sessionStartNanos=0;
        }
        listener.onInvalidated("stopped");
    }
    private void referenceMain(double lat,double lon,double altitude,long utc,long elapsed,boolean altitudeKnown,long lifecycleToken) {
        long now=SystemClock.elapsedRealtimeNanos();
        if (!Double.isFinite(lat) || Math.abs(lat)>90 || !Double.isFinite(lon) || Math.abs(lon)>180
                || !Double.isFinite(altitude) || utc<=0 || elapsed<=0 || elapsed>now || now-elapsed>1_500_000_000L) {
            synchronized(lock) { if (!desiredRunning || lifecycle!=lifecycleToken || activeLifecycle!=lifecycleToken) return; referenceValid=false; }
            invalidate("invalid_reference"); return;
        }
        double actualAltitude=altitudeKnown ? altitude : 0;
        double d=new GeomagneticField((float)lat,(float)lon,(float)actualAltitude,utc).getDeclination();
        if (!Double.isFinite(d)) { synchronized(lock) { if (!desiredRunning || lifecycle!=lifecycleToken || activeLifecycle!=lifecycleToken) return; referenceValid=false; } invalidate("invalid_reference"); return; }
        synchronized(lock) {
            if (!desiredRunning || lifecycle!=lifecycleToken || activeLifecycle!=lifecycleToken || !started || !rotationRegistered || !accelerationRegistered) return;
            if (referenceValid && elapsed<=referenceTime) return;
            declination=d; referenceTime=elapsed; referenceValid=true; referenceAltitudeAssumed=!altitudeKnown;
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {
        if (Looper.myLooper()!=Looper.getMainLooper()) { main.post(() -> onAccuracyChanged(sensor,accuracy)); return; }
        boolean active;
        synchronized(lock) {
            active=started;
            if (sensor==gyroscope) {
                if (accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) orientations.clearGyroscope();
                return;
            } else if (sensor==rotation) rotationAccuracy=accuracy;
            else if (sensor==acceleration) accelerationAccuracy=accuracy;
            else if (sensor==gravity) gravityAccuracy=accuracy;
            else if (sensor==magnetometer) magneticAccuracy=accuracy;
            else return;
            if (sensor==rotation && accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) { orientations.clear(); headingAccuracy="unknown"; }
            if (sensor==gravity && accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) { gravityValues=null;gravityTime=0; }
            if (sensor==magnetometer && accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) { magneticValues=null;magneticTime=0;orientations.clear(); }
        }
        if (active && accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM && (sensor==rotation || sensor==acceleration || (gravityFallback && sensor==gravity) || (degradedRotation && sensor==magnetometer))) invalidate("sensor_accuracy_low");
    }
    @Override public void onSensorChanged(SensorEvent event) {
        // Registration specifies the main Handler; listeners must remain serialized here.
        if (Looper.myLooper()!=Looper.getMainLooper()) { main.post(() -> invalidate("wrong_thread")); return; }
        synchronized(lock) { if (!desiredRunning || lifecycle!=activeLifecycle || !started || !rotationRegistered || !accelerationRegistered) return; }
        long now=SystemClock.elapsedRealtimeNanos(), t=event.timestamp;
        if(t<sessionStartNanos) return; // queued callback from an earlier registration epoch
        if(event.sensor==gyroscope) {
            synchronized(lock) {
                if (!gyroRegistered) return;
                if (t<=0 || t>now || event.accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
                    orientations.clearGyroscope();
                else orientations.addGyroscope(t,event.values,gyroscope.getMaximumRange());
            }
            return;
        }
        if(event.sensor==pressure) {
            if(t>0 && t<=now && t>pressureTime && event.values!=null && event.values.length>0 && Float.isFinite(event.values[0]) && event.values[0]>100 && event.values[0]<1200) synchronized(lock) { pressureHpa=event.values[0];pressureTime=t; }
            return;
        }
        if(event.sensor==gravity || event.sensor==magnetometer) {
            if(event.values==null || event.values.length<3 || t<=0 || t>now || event.accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) { synchronized(lock) { if(event.sensor==gravity){gravityValues=null;gravityTime=0;}else{magneticValues=null;magneticTime=0;orientations.clear();} } invalidate("invalid_sensor_sample");return; }
            for(int i=0;i<3;i++) if(!Float.isFinite(event.values[i])) { synchronized(lock) { if(event.sensor==gravity){gravityValues=null;gravityTime=0;}else{magneticValues=null;magneticTime=0;orientations.clear();} } invalidate("invalid_sensor_sample");return; }
            boolean outOfOrder;
            synchronized(lock) {
                outOfOrder=event.sensor==gravity?t<=gravityTime:t<=magneticTime;
                if(event.sensor==gravity) { if(outOfOrder) gravityValues=null; else {gravityValues=event.values.clone();gravityTime=t;gravityAccuracy=event.accuracy;} }
                else { if(outOfOrder) {magneticValues=null;orientations.clear();} else {magneticValues=event.values.clone();magneticTime=t;magneticAccuracy=event.accuracy;} }
            }
            if(outOfOrder) invalidate("sensor_timing");
            return;
        }
        if (t<=0 || t>now || event.accuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            if (event.sensor==rotation) synchronized(lock) { orientations.clear(); }
            invalidate("invalid_sensor_sample"); return;
        }
        if (event.sensor==rotation) {
            if(degradedRotation && !MotionSensorPolicy.magneticValid(magneticValues,magneticAccuracy,magneticTime,t)) { synchronized(lock) { orientations.clear(); } invalidate("magnetic_unavailable");return; }
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
        if(degradedRotation && !MotionSensorPolicy.magneticValid(magneticValues,magneticAccuracy,magneticTime,t)) { synchronized(lock) {orientations.clear();} invalidate("magnetic_unavailable");return; }
        float[] current; double d, angularSpeed; String issue=null;
        synchronized(lock) {
            String decision=accelerationGate.observe(t,referenceTime,now,referenceValid);
            if ("ignore".equals(decision)) return;
            if (!"accept".equals(decision)) issue=decision;
            else accelerationAccuracy=event.accuracy;
            current=orientations.at(t);
            angularSpeed=orientations.angularSpeedAt(t);
            if (issue==null && (current==null || rotationAccuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)) issue="direction_unavailable";
            d=declination;
        }
        if (issue!=null) { invalidate(issue); return; }
        try {
            float[] input=event.values;
            if(gravityFallback) {
                if(gravityAccuracy<SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) throw new IllegalArgumentException();
                double[] linear=GravitySubtraction.subtract(input,t,gravityValues,gravityTime,now,rawAcceleration.getMaximumRange());
                input=new float[]{(float)linear[0],(float)linear[1],(float)linear[2]};
            }
            double[] horizontal=MotionTransform.horizontal(current,input,d);
            double magnitude=Math.hypot(Math.hypot(input[0],input[1]),input[2]);
            synchronized(lock) { samples++; state="active"; }
            listener.onSample(t,horizontal[0],horizontal[1],magnitude,angularSpeed);
        } catch (IllegalArgumentException ex) { invalidate("invalid_acceleration"); }
    }
    double uncertaintyGrowthScale() { synchronized(lock) {return MotionSensorPolicy.uncertaintyGrowthScale(degradedRotation,gravityFallback,"known".equals(headingAccuracy));} }
    JSONObject snapshot() {
        synchronized(lock) {
            try { return new JSONObject().put("state",state).put("started",started)
                    .put("rotation_available",standardRotation!=null || geomagneticRotation!=null).put("acceleration_available",linearAcceleration!=null || (rawAcceleration!=null && gravity!=null))
                    .put("gyro_available",gyroscope!=null).put("gyro_registered",gyroRegistered).put("magnetic_available",magnetometer!=null)
                    .put("gravity_available",gravity!=null).put("raw_acceleration_available",rawAcceleration!=null)
                    .put("standard_rotation_available",standardRotation!=null).put("geomagnetic_rotation_available",geomagneticRotation!=null)
                    .put("linear_acceleration_available",linearAcceleration!=null).put("pressure_available",pressure!=null)
                    .put("selected_backend",!rotationRegistered || !accelerationRegistered ? "unavailable" : (degradedRotation?"geomagnetic":"rotation_vector")+"+"+(gravityFallback?"gravity_subtraction":"linear_acceleration"))
                    .put("degraded",degradedRotation || gravityFallback || !"known".equals(headingAccuracy))
                    .put("request_hz",20).put("prediction_horizon_s",JSONObject.NULL)
                    .put("uncertainty_growth_scale",uncertaintyGrowthScale())
                    .put("reference_age_s",referenceValid?(SystemClock.elapsedRealtimeNanos()-referenceTime)/(double)SECOND:JSONObject.NULL)
                    .put("pressure_state",!started?"stopped":!pressureRegistered?"unavailable":pressureTime==0?"waiting":SystemClock.elapsedRealtimeNanos()-pressureTime>5*SECOND?"stale":"active")
                    .put("pressure_hpa",pressureTime==0 || SystemClock.elapsedRealtimeNanos()-pressureTime>5*SECOND?JSONObject.NULL:pressureHpa)
                    .put("rotation_registered",rotationRegistered).put("acceleration_registered",accelerationRegistered)
                    .put("rotation_accuracy",rotationAccuracy).put("acceleration_accuracy",accelerationAccuracy)
                    .put("heading_accuracy",headingAccuracy).put("reference_valid",referenceValid)
                    .put("reference_altitude_assumed",referenceValid && referenceAltitudeAssumed)
                    .put("samples",samples).put("rejected",rejected); }
            catch (Exception impossible) { return new JSONObject(); }
        }
    }
}
