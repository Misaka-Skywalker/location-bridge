package org.locationbridge.source;

/** High-speed selection changes ranking only, never reported measurement accuracy. */
public final class AdaptiveSelectionTest {
    static final long S=1_000_000_000L;
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static Selector.Fix fix(String source,double accuracy,long time) {
        return new Selector.Fix(source,30,104,accuracy,time,false,1000,time);
    }
    public static void main(String[] args) {
        long t=100*S;
        NativeLocationPolicy p=new NativeLocationPolicy();
        p.setAgingRate(252);
        check(p.offer(fix("gps",5,t),t),"initial precise point");
        check(p.offer(fix("fused",50,t+S),t+S),"fresh challenger");
        check(p.offer(fix("fused",50,t+2*S),t+2*S),"confirmed fresh challenger");
        Selector.Fix selected=p.selected(t+2*S);
        check(selected!=null && selected.source.equals("fused"),"stale precise point loses at high speed");
        check(selected.accuracy==50,"selection does not invent tighter accuracy");
        check(selected.latitude==30 && selected.longitude==104,"measurement coordinates preserved");
        p.setAgingRate(Double.NaN);
        check(p.offer(fix("gps",6,t+3*S),t+3*S),"bad adaptation cannot poison selector");
        System.out.println("AdaptiveSelection tests passed");
    }
}
