package org.locationbridge.source;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ExternalPositionStoreTest {
    static final long UTC=1794557350000L, S=1_000_000_000L;
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
    static byte[] point(int second,int latitude,boolean unknown) {
        return ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN).putShort((short)0xc4)
            .putInt(latitude).putInt(1040600000).putShort((short)(unknown?0:2026))
            .put(new byte[]{11,13,8,9,(byte)second}).array();
    }
    public static void main(String[] args) {
        ExternalUseGate gate=new ExternalUseGate(false);
        ExternalPositionStore store=new ExternalPositionStore(gate);
        store.onLocation(point(10,305700000,false),1,UTC,100*S);
        check(store.selected(100*S,UTC).sequence==1,"first approved candidate");
        store.onLocation(point(9,100000000,false),2,UTC+1000,101*S);
        store.onLocation(point(10,100000000,false),3,UTC+1000,101*S);
        store.onLocation(point(10,100000000,true),4,UTC+1000,101*S);
        store.onLocation(point(20,100000000,false),5,UTC+1000,101*S);
        check(store.selected(101*S,UTC+1000).sequence==1,"rejected raw cannot replace approved candidate");
        check(store.selected(101*S,UTC+1000).position.latitude==30.57,"original coordinates retained");
        gate.onTxt(102*S);
        check(store.selected(102*S,UTC+2000)==null,"TXT hides stored candidate");
        store.onLocation(point(10,100000000,false),6,UTC+2000,102*S);
        check(store.selected(102*S,UTC+2000)==null,"old raw cannot undo TXT");
        store.onLocation(point(13,305800000,false),7,UTC+3000,103*S);
        check(store.selected(103*S,UTC+3000).sequence==7,"fresh later point returns");
        gate.setManualExcluded(true,104*S);
        store.onLocation(point(14,305900000,false),8,UTC+4000,104*S);
        check(store.selected(104*S,UTC+4000)==null,"manual prevents publication");
        gate.setManualExcluded(false,105*S);
        store.onLocation(point(15,305900000,false),9,UTC+5000,105*S);
        check(store.selected(105*S,UTC+5000)==null,"pre-resume callback cannot publish");
        store.onLocation(point(16,306000000,false),10,UTC+6000,106*S);
        check(store.selected(106*S,UTC+6000).sequence==10,"post-resume point publishes");
        check(store.selected(117*S,UTC+17000)==null,"stored approved point expires");
        gate.resetSession();store.clear();
        check(store.selected(117*S,UTC+17000)==null,"new session cannot reuse candidate");
        for (int status : new int[]{0,2,3}) {
            ExternalUseGate sourceGate=new ExternalUseGate(false);
            ExternalPositionStore source=new ExternalPositionStore(sourceGate);
            source.onLocation(point(10,305700000,false),1,UTC,100*S);
            byte[] invalid=point(11,305700000,false);
            int flags=0x44 | (status << 7);invalid[0]=(byte)flags;invalid[1]=(byte)(flags >> 8);
            source.onLocation(invalid,2,UTC+1000,101*S);
            check(source.selected(101*S,UTC+1000)==null,"explicit loss of valid position removes old candidate");
            source.onLocation(point(10,305700000,false),3,UTC+2000,102*S);
            check(source.selected(102*S,UTC+2000)==null,"pre-loss sample cannot restore source");
            source.onLocation(point(13,305800000,false),4,UTC+3000,103*S);
            check(source.selected(103*S,UTC+3000).sequence==4,"post-loss valid position restores source");
        }
        System.out.println("ExternalPositionStore tests passed");
    }
}
