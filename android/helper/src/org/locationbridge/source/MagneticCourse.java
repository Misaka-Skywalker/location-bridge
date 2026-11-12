package org.locationbridge.source;

/** GNSS track conversion; east-positive magnetic declination, degrees clockwise from north. */
final class MagneticCourse {
    static Double fromTrue(double trueCourse, double declination) {
        if (!Double.isFinite(trueCourse) || trueCourse < 0 || trueCourse >= 360
                || !Double.isFinite(declination) || Math.abs(declination) > 180) return null;
        return (trueCourse - declination + 360) % 360;
    }
}
