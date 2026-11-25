package org.locationbridge.source;

import java.util.Locale;

public final class MotionTrackReplayTest {
    private static final long SECOND=1_000_000_000L, START=20*SECOND;
    private static final double EAST_DEG=111319.4908, NORTH_DEG=110574.2758;

    private static void check(boolean value,String message) {
        if (!value) throw new AssertionError(message);
    }
    private static Selector.Fix fix(String source,long t,double east,double north) {
        return new Selector.Fix(source,north/NORTH_DEG,east/EAST_DEG,6,t,false,1000,t);
    }
    private static void arc(int sign,boolean noisy) {
        PositionPublication publication=new PositionPublication();
        double speed=8, rate=sign*Math.PI/36, sum=0, maxLag=0, lastEast=0,lastNorth=0;
        int scored=0;
        for (int i=0;i<=18;i++) {
            long t=START+i*SECOND;
            double angle=rate*i, east=speed/rate*(1-Math.cos(angle)),north=speed/rate*Math.sin(angle);
            double jitter=noisy && i>0 ? (i%2==0?10:-10) : 0;
            Selector.Fix raw=fix("gps",t,east+jitter*Math.cos(angle),north-jitter*Math.sin(angle));
            publication.observeGpsMotion(t,t,speed,.1,(360+Math.toDegrees(angle))%360,1);
            PositionPublication.Output out=publication.select(raw,null,t);
            check(out.measured==raw && out.elapsed==t && out.position.accuracy==raw.accuracy,"raw provenance");
            double de=out.position.longitude*EAST_DEG-east,dn=out.position.latitude*NORTH_DEG-north;
            if (i>=3) {
                sum+=de*de+dn*dn;
                maxLag=Math.max(maxLag, Math.abs(de*Math.sin(angle)+dn*Math.cos(angle))/speed);
                scored++;
            }
            lastEast=east; lastNorth=north;
        }
        double rms=Math.sqrt(sum/scored);
        System.out.printf(Locale.ROOT,"%s 90-degree turn, %s: RMS %.2f m, max along-track timing error %.3f s%n",
                sign>0?"Right":"Left",noisy?"alternating 10 m noise":"clean",rms,maxLag);
        check(rms<(noisy?4:.05), "turn accuracy");
        check(maxLag<.15,"turn follows current measurement time");
        for (int i=19;i<=23;i++) {
            long t=START+i*SECOND;
            publication.observeGpsMotion(t,t,0,.1,Double.NaN,Double.NaN);
            PositionPublication.Output stopped=publication.select(fix("gps",t,lastEast,lastNorth),null,t);
            check(Math.hypot(stopped.position.longitude*EAST_DEG-lastEast,stopped.position.latitude*NORTH_DEG-lastNorth)<.01,
                    "no position tail after stop");
        }
    }
    private static void asynchronousAcceleration() {
        PositionPublication publication=new PositionPublication();
        // Position fixes arrive a quarter second behind the latest GNSS velocity.
        // Constant eastward acceleration gives an independently known displacement.
        for (int i=0;i<=24;i++) {
            long now=START+i*SECOND/2;
            double seconds=i*.5;
            publication.observeGpsMotion(now,now,20+2*seconds,.1,90,1);
            if (i%2==0) continue;
            double at=seconds-.25, east=20*at+at*at;
            Selector.Fix raw=fix("fused",now-SECOND/4,east,(i%4==1?7:-7));
            PositionPublication.Output out=publication.select(raw,null,now);
            check(out.elapsed==raw.elapsed && out.measured==raw,"asynchronous sample retains its time and source");
            if (i>7) {
                check("velocity_track".equals(out.positionFilter),"bracketed fused measurement");
                check(Math.abs(out.position.longitude*EAST_DEG-east)<.02,"accelerating track remains on time");
                check(Math.abs(out.position.latitude*NORTH_DEG)<3.1,"lateral jitter while accelerating");
                check(publication.select(raw,null,now+SECOND/10)==out,"polling has no smoothing side effect");
            }
        }
    }

    private static void evidenceAndRecovery() {
        PositionPublication p=new PositionPublication();
        p.observeGpsMotion(START,START,70,.2,90,.5);
        p.select(fix("gps",START,0,0),null,START);
        long t=START+SECOND;
        p.observeGpsMotion(t,t,70,.2,90,.5);
        Selector.Fix noisy=fix("gps",t,70,8);
        check("velocity_track".equals(p.select(noisy,null,t).positionFilter),"high-speed measured fix can be smoothed");
        t+=SECOND;
        p.observeGpsMotion(t,t,70,.2,90,.5);
        Selector.Fix corrected=fix("gps",t,140,80);
        check(p.select(corrected,null,t).position==corrected,"large correction passes immediately");
        t+=SECOND;
        p.observeGpsMotion(t,t,70,.2,90,.5);
        Selector.Fix changed=fix("fused",t,210,79);
        check(p.select(changed,null,t).position==changed,"source change passes raw");
        t+=SECOND;
        check(p.select(null,null,t)==null,"no GNSS fix produces no synthetic position");
        t+=3*SECOND;
        Selector.Fix returned=fix("gps",t,400,73);
        p.observeGpsMotion(t,t,70,.2,90,.5);
        check(p.select(returned,null,t).position==returned,"first reacquired position passes raw");
        t+=SECOND;
        p.observeGpsMotion(t,t,70,.2,90,8);
        Selector.Fix weakCourse=fix("gps",t,470,80);
        check(p.select(weakCourse,null,t).position==weakCourse,"uncertain displacement passes raw");
        t+=SECOND;
        p.observeGpsMotion(t,t,70,.2,270,.5);
        Selector.Fix reversal=fix("gps",t,465,76);
        check(p.select(reversal,null,t).position==reversal,"inconsistent velocity pair clears history");
    }

    public static void main(String[] args) {
        arc(1,true); arc(-1,true); arc(1,false); arc(-1,false);
        asynchronousAcceleration();
        evidenceAndRecovery();
    }
}
