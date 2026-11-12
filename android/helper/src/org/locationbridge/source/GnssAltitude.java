package org.locationbridge.source;

/** Same-sample WGS84 ellipsoid altitude from a valid GPS-provider fix. */
final class GnssAltitude {
    static final long MAX_AGE_NANOS = 10_000_000_000L;
    static final class Reading {
        final long elapsed;
        final boolean hasAltitude;
        final double altitude;
        final Double verticalAccuracy;
        Reading(long elapsed, boolean hasAltitude, double altitude, Double verticalAccuracy) {
            this.elapsed=elapsed; this.hasAltitude=hasAltitude; this.altitude=altitude; this.verticalAccuracy=verticalAccuracy;
        }
    }
    private Reading latest;
    synchronized boolean update(String provider, boolean mock, long elapsed, long now,
                                double latitude, double longitude, boolean hasAltitude, double altitude,
                                boolean hasVerticalAccuracy, double verticalAccuracy) {
        if (!"gps".equals(provider) || mock || elapsed <= 0 || elapsed > now
                || !Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180
                || (latest != null && elapsed <= latest.elapsed)) return false;
        boolean validAltitude = hasAltitude && Double.isFinite(altitude);
        Double accuracy = validAltitude && hasVerticalAccuracy && Double.isFinite(verticalAccuracy) && verticalAccuracy >= 0
                ? Double.valueOf(verticalAccuracy) : null;
        latest = new Reading(elapsed, validAltitude, validAltitude ? altitude : 0, accuracy);
        return true;
    }
    synchronized Reading fresh(long now) {
        if (latest == null || now < latest.elapsed || now - latest.elapsed > MAX_AGE_NANOS) return null;
        return latest;
    }
    synchronized void clear() { latest = null; }
}
