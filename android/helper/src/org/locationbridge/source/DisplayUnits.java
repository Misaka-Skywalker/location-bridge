package org.locationbridge.source;

import java.util.Locale;

/** Display conversions only; transport and motion calculations retain SI units. */
final class DisplayUnits {
    static String speed(double metersPerSecond) {
        double kmh=metersPerSecond*3.6,kts=metersPerSecond*3600/1852;
        if(!Double.isFinite(kmh) || !Double.isFinite(kts) || metersPerSecond<0) return "---";
        return String.format(Locale.ROOT,"%.1f km/h\n%.1f kts",kmh,kts);
    }
    static String distance(double meters,boolean signed) {
        if(!Double.isFinite(meters)) return "---";
        return String.format(Locale.ROOT,Math.abs(meters)>=1000?(signed?"%+.2f km":"%.2f km"):(signed?"%+.1f m":"%.1f m"),
                Math.abs(meters)>=1000?meters/1000:meters);
    }
    static String duration(double seconds) {
        if(!Double.isFinite(seconds) || seconds<0) return "---";
        return String.format(Locale.ROOT,seconds<60?"%.1f s":seconds<3600?"%.1f min":"%.2f h",
                seconds<60?seconds:seconds<3600?seconds/60:seconds/3600);
    }
}
