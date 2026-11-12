package org.locationbridge.source;
public final class MotionOrientationHistoryTest {
    static void check(boolean value,String text) { if (!value) throw new AssertionError(text); }
    public static void main(String[] args) {
        MotionOrientationHistory h=new MotionOrientationHistory();
        float[] a={1,0,0,0,1,0,0,0,1}, b={0,-1,0,1,0,0,0,0,1};
        h.add(1_000_000_000L,a); h.add(1_050_000_000L,b);
        check(h.at(1_025_000_000L)[0]==1,"uses previous orientation when latest is newer");
        check(h.at(1_050_000_000L)[0]==0,"uses newest matching orientation");
        check(h.at(1_300_000_000L)!=null,"250ms boundary");
        check(h.at(1_300_000_001L)==null,"reject stale orientation");
        h.clear(); check(h.at(1_050_000_000L)==null,"invalid heading clears history");
        for(int i=1;i<=9;i++) h.add(i*1_000_000L,a);
        check(h.at(1_000_000L)==null,"bounded history");
        System.out.println("Motion orientation history tests passed");
    }
}
