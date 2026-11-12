package org.locationbridge.source;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class LnsDecoderTest {
    static void check(boolean c, String s) { if (!c) throw new AssertionError(s); }
    static void near(Double x, double n) { check(x != null && Math.abs(x-n) < 0.000001, "expected " + n + " got " + x); }
    static void bad(Runnable r) { try { r.run(); throw new AssertionError("expected invalid payload"); } catch (IllegalArgumentException expected) { } }
    public static void main(String[] args) {
        ByteBuffer b = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short)0x00ff).putShort((short)1234).put(new byte[]{7,0,0})
         .putInt(-305700000).putInt(1040600000).put(new byte[]{(byte)0x2e,(byte)0xfb,(byte)0xff})
         .putShort((short)12345).put((byte)2).putShort((short)2026)
         .put(new byte[]{11,13,8,9,10});
        byte[] vector=b.array();
        LnsDecoder.LocationSpeed loc=LnsDecoder.decodeLocationSpeed(vector);
        check(loc.validPosition && loc.positionStatus==1, "position status");
        near(loc.latitude,-30.57); near(loc.longitude,104.06); near(loc.speedMps,12.34);
        near(loc.elevationM,-12.34); near(loc.headingDeg,123.45);
        check(loc.utcMillis == 1794557350000L, "UTC");
        for (int i=0;i<vector.length;i++) { final int n=i; bad(() -> LnsDecoder.decodeLocationSpeed(Arrays.copyOf(vector,n))); }
        byte[] stale=vector.clone(); stale[1]=1; // status 3, not a fresh fix
        check(!LnsDecoder.decodeLocationSpeed(stale).validPosition, "last known invalid");
        byte[] estimated=vector.clone(); estimated[0]=(byte)((estimated[0] & 0x7f)); estimated[1]=1;
        check(!LnsDecoder.decodeLocationSpeed(estimated).validPosition, "estimated fix rejected");
        check(!LnsDecoder.decodeLocationSpeed(new byte[]{(byte)0x80,0}).validPosition, "position without coordinates");
        byte[] invalidDate=vector.clone(); invalidDate[23]=13; bad(() -> LnsDecoder.decodeLocationSpeed(invalidDate));
        byte[] invalidHeading=vector.clone(); invalidHeading[18]=(byte)0xa0; invalidHeading[19]=(byte)0x8c;
        bad(() -> LnsDecoder.decodeLocationSpeed(invalidHeading));
        byte[] reserved=vector.clone(); reserved[1]|=0x20; bad(() -> LnsDecoder.decodeLocationSpeed(reserved));
        byte[] unknown=vector.clone(); unknown[21]=0; unknown[22]=0;
        check(LnsDecoder.decodeLocationSpeed(unknown).utcMillis==null, "unknown year");
        byte[] quality=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort((short)0xff)
                .put((byte)7).put((byte)9).putShort((short)30).putInt(525).putInt(1000).put((byte)6).put((byte)9).array();
        LnsDecoder.Quality q=LnsDecoder.decodeQuality(quality);
        check(q.positionStatus==1,"quality status"); near(q.horizontalErrorMeters,5.25); near(q.verticalErrorMeters,10); near(q.hdop,1.2); near(q.vdop,1.8);
        for (int i=0;i<quality.length;i++) { final int n=i; bad(() -> LnsDecoder.decodeQuality(Arrays.copyOf(quality,n))); }
        bad(() -> LnsDecoder.decodeQuality(new byte[]{0,2}));
        bad(() -> LnsDecoder.decodeQuality(new byte[]{0,0,0}));
        System.out.println("LNS decoder tests passed");
    }
}
