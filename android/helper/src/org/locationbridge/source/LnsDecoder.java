package org.locationbridge.source;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Stateless Bluetooth SIG LNS characteristic decoder. No timing or cross-characteristic merging. */
public final class LnsDecoder {
    private LnsDecoder() { }
    public static final class LocationSpeed {
        public final int flags, positionStatus, elevationSource, headingSource;
        public final Double latitude, longitude, speedMps, elevationM, headingDeg;
        public final Long utcMillis;
        public final boolean validPosition;
        private LocationSpeed(int flags, Double latitude, Double longitude, Double speedMps,
                              Double elevationM, Double headingDeg, Long utcMillis) {
            this.flags=flags; this.positionStatus=(flags >>> 7) & 3;
            this.elevationSource=(flags >>> 10) & 3; this.headingSource=(flags >>> 12) & 1;
            this.latitude=latitude; this.longitude=longitude; this.speedMps=speedMps;
            this.elevationM=elevationM; this.headingDeg=headingDeg; this.utcMillis=utcMillis;
            this.validPosition=positionStatus == 1 && latitude != null && longitude != null;
        }
    }
    public static final class Quality {
        public final int flags, positionStatus;
        public final Double horizontalErrorMeters, verticalErrorMeters, hdop, vdop;
        private Quality(int flags, Double h, Double v, Double hdop, Double vdop) {
            this.flags=flags; this.positionStatus=(flags >>> 7) & 3;
            this.horizontalErrorMeters=h; this.verticalErrorMeters=v; this.hdop=hdop; this.vdop=vdop;
        }
    }
    private static final class Reader {
        final byte[] data; int pos;
        Reader(byte[] data) { if (data == null) throw new IllegalArgumentException("null payload"); this.data=data; }
        int u8() { if (pos >= data.length) throw new IllegalArgumentException("truncated payload"); return data[pos++] & 255; }
        int u16() { int a=u8(); return a | u8()<<8; }
        int u24() { int a=u16(); return a | u8()<<16; }
        int i24() { int v=u24(); return (v & 0x800000) != 0 ? v | 0xff000000 : v; }
        long u32() { return (long)u16() | ((long)u16() << 16); }
        int i32() { return (int)u32(); }
        void end() { if (pos != data.length) throw new IllegalArgumentException("trailing payload"); }
    }
    private static Long utc(Reader r) {
        int year=r.u16(), month=r.u8(), day=r.u8(), hour=r.u8(), minute=r.u8(), second=r.u8();
        if ((year != 0 && (year < 1582 || year > 9999)) || month > 12 || day > 31 || hour > 23 || minute > 59 || second > 59)
            throw new IllegalArgumentException("invalid UTC fields");
        if (year == 0 || month == 0 || day == 0) return null;
        try { return LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC).toEpochMilli(); }
        catch (DateTimeException e) { throw new IllegalArgumentException("invalid UTC date", e); }
    }
    public static LocationSpeed decodeLocationSpeed(byte[] data) {
        Reader r=new Reader(data); int flags=r.u16();
        if ((flags & 0xe000) != 0) throw new IllegalArgumentException("reserved location flags");
        Double speed=null, latitude=null, longitude=null, elevation=null, heading=null;
        Long time=null;
        if ((flags & 1) != 0) speed=r.u16() / 100.0;
        if ((flags & 2) != 0) r.u24();
        if ((flags & 4) != 0) {
            latitude=r.i32() / 1e7; longitude=r.i32() / 1e7;
            if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) throw new IllegalArgumentException("coordinate out of range");
        }
        if ((flags & 8) != 0) elevation=r.i24() / 100.0;
        if ((flags & 16) != 0) { heading=r.u16() / 100.0; if (heading >= 360) throw new IllegalArgumentException("heading out of range"); }
        if ((flags & 32) != 0) r.u8();
        if ((flags & 64) != 0) time=utc(r);
        r.end();
        return new LocationSpeed(flags, latitude, longitude, speed, elevation, heading, time);
    }
    public static Quality decodeQuality(byte[] data) {
        Reader r=new Reader(data); int flags=r.u16();
        if ((flags & 0xfe00) != 0) throw new IllegalArgumentException("reserved quality flags");
        if ((flags & 1) != 0) r.u8();
        if ((flags & 2) != 0) r.u8();
        if ((flags & 4) != 0) r.u16();
        Double h=null, v=null, hdop=null, vdop=null;
        if ((flags & 8) != 0) h=r.u32() / 100.0;
        if ((flags & 16) != 0) v=r.u32() / 100.0;
        if ((flags & 32) != 0) hdop=r.u8() * 0.2;
        if ((flags & 64) != 0) vdop=r.u8() * 0.2;
        r.end();
        return new Quality(flags, h, v, hdop, vdop);
    }
}
