package org.locationbridge.source;

final class Selector {
    static final long SECOND = 1_000_000_000L;
    static final class Fix {
        final String source;
        final double latitude, longitude, accuracy;
        final long elapsed, timestamp, sequence;
        final boolean mock;
        Fix(String source, double latitude, double longitude, double accuracy, long elapsed,
            boolean mock, long timestamp, long sequence) {
            this.source=source; this.latitude=latitude; this.longitude=longitude;
            this.accuracy=accuracy; this.elapsed=elapsed; this.mock=mock;
            this.timestamp=timestamp; this.sequence=sequence;
        }
    }
    static boolean valid(Fix f, long now) {
        return f != null && !f.mock && f.elapsed > 0 && f.elapsed <= now
            && Double.isFinite(f.latitude) && Math.abs(f.latitude) <= 90
            && Double.isFinite(f.longitude) && Math.abs(f.longitude) <= 180
            && Double.isFinite(f.accuracy) && f.accuracy >= 0;
    }
    static Fix fresh(Fix f, long now, long maxAge) {
        return valid(f, now) && now - f.elapsed <= maxAge ? f : null;
    }
    static Fix choose(Fix fused, Fix gps, Fix network, long now) {
        fused = fresh(fused, now, 5 * SECOND);
        if (fused != null) return fused;
        gps = fresh(gps, now, 10 * SECOND);
        network = fresh(network, now, 10 * SECOND);
        if (gps == null) return network;
        if (network == null) return gps;
        if (gps.accuracy != network.accuracy) return gps.accuracy < network.accuracy ? gps : network;
        return gps.elapsed >= network.elapsed ? gps : network;
    }
}
