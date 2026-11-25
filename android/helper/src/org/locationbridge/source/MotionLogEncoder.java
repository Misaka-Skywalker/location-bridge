package org.locationbridge.source;

/** Bounded, per-sensor sampling of already subscribed device-frame readings. */
final class MotionLogEncoder {
    enum Sensor {
        LINEAR_ACCELERATION("linear_acceleration", false), RAW_ACCELERATION("raw_acceleration", false),
        GRAVITY("gravity", false), GYROSCOPE("gyroscope", false),
        ROTATION_VECTOR("rotation_vector", true), GEOMAGNETIC_ROTATION_VECTOR("geomagnetic_rotation_vector", true);
        final String name;
        final boolean rotation;
        Sensor(String name, boolean rotation) { this.name=name; this.rotation=rotation; }
    }
    private static final long PERIOD_NS=50_000_000L; // At most 20 Hz per sensor for long recordings.
    private final long[] last = new long[Sensor.values().length];

    String encode(Sensor sensor, long elapsedNanos, int accuracy, float[] values) {
        if (elapsedNanos<=0 || values==null || values.length<3) return null;
        long previous=last[sensor.ordinal()];
        if (previous!=0 && (elapsedNanos<=previous || elapsedNanos-previous<PERIOD_NS)) return null;
        int count=sensor.rotation ? Math.min(values.length,5) : 3;
        for (int i=0;i<count;i++) if (!Float.isFinite(values[i])) return null;
        float scalar=0, heading=-1;
        if (sensor.rotation) {
            scalar=values.length>3 ? values[3] : (float)Math.sqrt(Math.max(0,1.0-values[0]*values[0]-values[1]*values[1]-values[2]*values[2]));
            if (values.length>4) heading=values[4];
        }
        StringBuilder out=new StringBuilder(144);
        out.append("{\"type\":\"motion\",\"sensor\":\"").append(sensor.name)
                .append("\",\"elapsed_ns\":").append(elapsedNanos).append(",\"accuracy\":").append(accuracy)
                .append(",\"values\":[").append(values[0]).append(',').append(values[1]).append(',').append(values[2]);
        if (sensor.rotation) out.append(',').append(scalar).append(',').append(heading);
        out.append("]}");
        last[sensor.ordinal()]=elapsedNanos;
        return out.toString();
    }
}
