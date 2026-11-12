package org.locationbridge.source;

/** Transform device linear acceleration to true-east and true-north. */
final class MotionTransform {
    private MotionTransform() { }
    static double[] horizontal(float[] rotation, float[] acceleration, double declinationDegrees) {
        if (rotation == null || rotation.length != 9 || acceleration == null || acceleration.length < 3
                || !Double.isFinite(declinationDegrees)) throw new IllegalArgumentException("invalid transform");
        for (float x : rotation) if (!Float.isFinite(x)) throw new IllegalArgumentException("invalid rotation");
        for (int i=0;i<3;i++) if (!Float.isFinite(acceleration[i])) throw new IllegalArgumentException("invalid acceleration");
        double east = (double)rotation[0]*acceleration[0] + (double)rotation[1]*acceleration[1] + (double)rotation[2]*acceleration[2];
        double north = (double)rotation[3]*acceleration[0] + (double)rotation[4]*acceleration[1] + (double)rotation[5]*acceleration[2];
        double radians = Math.toRadians(declinationDegrees), c = Math.cos(radians), s = Math.sin(radians);
        return new double[]{east*c+north*s, -east*s+north*c};
    }
}
