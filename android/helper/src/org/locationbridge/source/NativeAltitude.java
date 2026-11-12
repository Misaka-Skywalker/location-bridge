package org.locationbridge.source;

/** Fresh WGS84 ellipsoid heights from independently ordered native providers. */
final class NativeAltitude {
    private static long maxAge(String source) { return "fused".equals(source) ? 5_000_000_000L : 10_000_000_000L; }

    static final class Reading {
        final String source;
        final long elapsed;
        final double altitude;
        final Double verticalAccuracy;
        Reading(String source, long elapsed, double altitude, Double verticalAccuracy) {
            this.source = source;
            this.elapsed = elapsed;
            this.altitude = altitude;
            this.verticalAccuracy = verticalAccuracy;
        }
    }

    private static final class State {
        long elapsed;
        Reading reading;
    }
    private final State gps = new State();
    private final State fused = new State();

    private State state(String provider) {
        return "gps".equals(provider) ? gps : "fused".equals(provider) ? fused : null;
    }

    synchronized boolean update(String provider, boolean mock, long elapsed, long now,
                                double latitude, double longitude, boolean hasAltitude, double altitude,
                                boolean hasVerticalAccuracy, double verticalAccuracy) {
        State s = state(provider);
        if (s == null || mock || elapsed <= 0 || elapsed > now || now - elapsed > maxAge(provider)
                || !Double.isFinite(latitude) || Math.abs(latitude) > 90
                || !Double.isFinite(longitude) || Math.abs(longitude) > 180
                || elapsed <= s.elapsed) return false;
        s.elapsed = elapsed;
        if (!hasAltitude || !Double.isFinite(altitude)) {
            s.reading = null;
        } else {
            Double accuracy = hasVerticalAccuracy && Double.isFinite(verticalAccuracy) && verticalAccuracy >= 0
                    ? Double.valueOf(verticalAccuracy) : null;
            s.reading = new Reading(provider, elapsed, altitude, accuracy);
        }
        return true;
    }

    private Reading fresh(State s, long now) {
        Reading r = s.reading;
        return r != null && now >= r.elapsed && now - r.elapsed <= maxAge(r.source) ? r : null;
    }

    synchronized Reading fresh(long now, Selector.Fix selectedPosition) {
        if (selectedPosition == null) return null;
        State preferred = state(selectedPosition.source);
        Reading chosen = preferred == null ? null : fresh(preferred, now);
        if (chosen != null) return chosen;
        Reading a = fresh(gps, now), b = fresh(fused, now);
        if (a == null) return b;
        if (b == null) return a;
        if (a.verticalAccuracy == null && b.verticalAccuracy != null) return b;
        if (a.verticalAccuracy != null && b.verticalAccuracy == null) return a;
        if (a.verticalAccuracy != null) {
            int compare = Double.compare(a.verticalAccuracy, b.verticalAccuracy);
            if (compare != 0) return compare < 0 ? a : b;
        }
        return a.elapsed >= b.elapsed ? a : b;
    }

    synchronized void remove(String provider) {
        State s = state(provider);
        if (s != null) { s.elapsed = 0; s.reading = null; }
    }

    synchronized void clear() { remove("gps"); remove("fused"); }
}
