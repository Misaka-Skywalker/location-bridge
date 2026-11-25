package org.locationbridge.source;

public final class MotionLogEncoderTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        MotionLogEncoder log = new MotionLogEncoder();
        String line = log.encode(MotionLogEncoder.Sensor.LINEAR_ACCELERATION, 1_000_000_000L, 0, new float[]{1, -2, 3});
        check("{\"type\":\"motion\",\"sensor\":\"linear_acceleration\",\"elapsed_ns\":1000000000,\"accuracy\":0,\"values\":[1.0,-2.0,3.0]}".equals(line));
        check(log.encode(MotionLogEncoder.Sensor.LINEAR_ACCELERATION, 1_049_999_999L, 3, new float[]{1,2,3}) == null);
        check(log.encode(MotionLogEncoder.Sensor.GYROSCOPE, 1_010_000_000L, 1, new float[]{0,1,2}) != null);
        check(log.encode(MotionLogEncoder.Sensor.LINEAR_ACCELERATION, 1_050_000_000L, 3, new float[]{1,2,3}) != null);
        check(log.encode(MotionLogEncoder.Sensor.GRAVITY, 2_000_000_000L, 3, new float[]{Float.NaN,2,3}) == null);
        check(log.encode(MotionLogEncoder.Sensor.GRAVITY, 2_000_000_001L, 3, new float[]{1,2,3}) != null);
        check(log.encode(MotionLogEncoder.Sensor.ROTATION_VECTOR, 3_000_000_000L, 1, new float[]{0,0,0}).endsWith("\"values\":[0.0,0.0,0.0,1.0,-1.0]}"));
        System.out.println("MotionLogEncoderTest passed");
    }
}
