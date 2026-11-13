package org.locationbridge.source;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Aggregates status entries without exposing satellite identifiers. */
final class GnssQuality {
    static final long MAX_AGE_NANOS = 10_000_000_000L;
    private final Set<String> visible = new HashSet<>(), used = new HashSet<>();
    private final Map<String, Set<String>> constellations = new HashMap<>();
    private final Map<String, Integer> bands = new HashMap<>();
    private int signals, cn0Count;
    private double cn0Sum, cn0Max = Double.NEGATIVE_INFINITY;

    void add(int constellation, int svid, boolean usedInFix, double cn0, double hz) {
        signals++;
        String type = constellation(constellation);
        if (svid > 0) {
            String key = constellation + ":" + svid;
            visible.add(key);
            if (usedInFix) used.add(key);
            constellations.computeIfAbsent(type, unused -> new HashSet<>()).add(key);
        }
        if (Double.isFinite(cn0) && cn0 >= 0 && cn0 <= 63) {
            cn0Count++; cn0Sum += cn0; cn0Max = Math.max(cn0Max, cn0);
        }
        String band = band(constellation, hz);
        bands.put(band, bands.getOrDefault(band, 0) + 1);
    }

    static boolean validTime(long event, long now, long previous) {
        return event > previous && event > 0 && now >= event && now - event <= 2_000_000_000L;
    }

    static String constellation(int type) {
        switch (type) {
            case 1: return "gps"; case 2: return "sbas"; case 3: return "glonass";
            case 4: return "qzss"; case 5: return "beidou"; case 6: return "galileo";
            case 7: return "irnss"; default: return "unknown";
        }
    }
    static String band(int type, double hz) {
        if (!Double.isFinite(hz) || hz <= 0) return "unknown";
        double mhz = hz / 1_000_000d;
        if (type == 1 || type == 2 || type == 4) {
            String prefix = type == 1 ? "gps" : type == 2 ? "sbas" : "qzss";
            if (Math.abs(mhz-1575.42)<2) return prefix+"_l1";
            if (Math.abs(mhz-1227.60)<2 && type != 2) return prefix+"_l2";
            if (Math.abs(mhz-1176.45)<2) return prefix+"_l5";
        } else if (type == 6) {
            if (Math.abs(mhz-1575.42)<2) return "galileo_e1";
            if (Math.abs(mhz-1176.45)<2) return "galileo_e5a";
            if (Math.abs(mhz-1207.14)<2) return "galileo_e5b";
            if (Math.abs(mhz-1278.75)<2) return "galileo_e6";
        } else if (type == 5) {
            if (Math.abs(mhz-1561.098)<2) return "beidou_b1i";
            if (Math.abs(mhz-1575.42)<2) return "beidou_b1c";
            if (Math.abs(mhz-1176.45)<2) return "beidou_b2a";
            if (Math.abs(mhz-1207.14)<2) return "beidou_b2i_b2b";
            if (Math.abs(mhz-1268.52)<2) return "beidou_b3";
        } else if (type == 3) {
            if (mhz >= 1598 && mhz <= 1607) return "glonass_g1";
            if (mhz >= 1243 && mhz <= 1250) return "glonass_g2";
            if (Math.abs(mhz-1202.025)<2) return "glonass_g3";
        } else if (type == 7 && Math.abs(mhz-1176.45)<2) return "irnss_l5";
        return "other";
    }
    Summary finish(long elapsed) {
        Map<String,Integer> counts = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : constellations.entrySet()) counts.put(e.getKey(), e.getValue().size());
        return new Summary(elapsed, visible.size(), used.size(), signals, cn0Count,
                cn0Count == 0 ? null : cn0Sum/cn0Count, cn0Count == 0 ? null : cn0Max,
                counts, new HashMap<>(bands));
    }
    static final class Summary {
        final long elapsed;
        final int visible, used, signals, cn0Count;
        final Double cn0Mean, cn0Max;
        final Map<String,Integer> constellations, bands;
        Summary(long elapsed, int visible, int used, int signals, int cn0Count, Double cn0Mean,
                Double cn0Max, Map<String,Integer> constellations, Map<String,Integer> bands) {
            this.elapsed=elapsed; this.visible=visible; this.used=used; this.signals=signals; this.cn0Count=cn0Count;
            this.cn0Mean=cn0Mean; this.cn0Max=cn0Max;
            this.constellations=Collections.unmodifiableMap(constellations); this.bands=Collections.unmodifiableMap(bands);
        }
        boolean fresh(long now) { return elapsed > 0 && now >= elapsed && now - elapsed <= MAX_AGE_NANOS; }
    }
}
