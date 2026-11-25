package org.locationbridge.source;

public final class GroundTrackFilterTest {
    private static final long SECOND=1_000_000_000L, START=100*SECOND;
    private static final double METRES_PER_DEGREE=111319.49;

    private static void check(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Selector.Fix fix(String source,long time,double east,double north,double accuracy) {
        return new Selector.Fix(source,north/METRES_PER_DEGREE,east/METRES_PER_DEGREE,accuracy,time,
                false,1_700_000_000_000L+(time-START)/1_000_000L,time/SECOND);
    }

    private static void velocity(GroundTrackFilter filter,long time,double speed,double course) {
        filter.observeVelocity(time,speed,.2,course,2,time);
    }

    private static void straight() {
        GroundTrackFilter filter=new GroundTrackFilter();
        Selector.Fix last=null;
        double maxStep=0;
        for (int i=0;i<30;i++) {
            long time=START+i*SECOND;
            velocity(filter,time,5,0);
            Selector.Fix raw=fix("gps",time,i%2==0?10:-10,5*i,6);
            Selector.Fix out=filter.apply(last,raw,time);
            check(out.latitude==raw.latitude,"northbound along-track position is measured");
            check(out.elapsed==raw.elapsed && out.timestamp==raw.timestamp && out.sequence==raw.sequence
                    && out.accuracy==raw.accuracy && raw.source.equals(out.source),"preserve measurement metadata");
            if (i<3) check(out==raw,"wait for continuous straight motion");
            if (i>12) maxStep=Math.max(maxStep,Math.abs(out.longitude-last.longitude)*METRES_PER_DEGREE);
            last=out;
        }
        check(maxStep<5,"moving lateral jumps after settling: "+maxStep);
        System.out.printf(java.util.Locale.ROOT,"Straight moving replay: raw 20 m jumps, filtered %.2f m%n",maxStep);
    }

    private static Selector.Fix established(GroundTrackFilter filter) {
        Selector.Fix last=null;
        for (int i=0;i<=4;i++) {
            long time=START+i*SECOND;
            velocity(filter,time,5,0);
            Selector.Fix raw=fix("gps",time,i%2==0?10:-10,5*i,6);
            last=filter.apply(last,raw,time);
            if (i==4) check(last!=raw,"test begins with filtering engaged");
        }
        return last;
    }

    private static void preserveObliqueAlongTrack() {
        GroundTrackFilter filter=new GroundTrackFilter();
        Selector.Fix last=null;
        double course=Math.toDegrees(Math.atan2(.6,.8));
        for (int i=0;i<10;i++) {
            long time=START+i*SECOND;
            velocity(filter,time,5,course);
            double lateral=i%2==0?10:-10;
            Selector.Fix raw=fix("gps",time,.6*5*i+.8*lateral,.8*5*i-.6*lateral,6);
            Selector.Fix out=filter.apply(last,raw,time);
            if (i>=4) {
                double east=(out.longitude-raw.longitude)*METRES_PER_DEGREE;
                double north=(out.latitude-raw.latitude)*METRES_PER_DEGREE;
                check(Math.abs(.6*east+.8*north)<1e-6,"filter preserves oblique along-track component");
                check(Math.abs(.8*east-.6*north)>.1,"oblique path still filters lateral noise");
            }
            last=out;
        }
    }

    private static void bypassMotionChanges() {
        GroundTrackFilter filter=new GroundTrackFilter();
        Selector.Fix last=established(filter);
        long time=last.elapsed+SECOND;
        velocity(filter,time,5,30);
        Selector.Fix raw=fix("gps",time,8,25,6);
        check(filter.apply(last,raw,time)==raw,"turn bypasses on the first changed course");

        filter=new GroundTrackFilter();
        last=established(filter);
        double rate=Math.toRadians(.9);
        for (int i=1;i<=12;i++) {
            time=START+(4+i)*SECOND;
            velocity(filter,time,5,.9*i);
            double east=5/rate*(1-Math.cos(rate*i))+(i%2==0?10:-10);
            double north=20+5/rate*Math.sin(rate*i);
            raw=fix("gps",time,east,north,6);
            last=filter.apply(last,raw,time);
            if (i>=3) check(last==raw,"sustained gentle turn must stay unfiltered");
        }

        filter=new GroundTrackFilter();
        last=established(filter);
        time=last.elapsed+SECOND;
        velocity(filter,time,7,0);
        raw=fix("gps",time,-10,27,6);
        check(filter.apply(last,raw,time)==raw,"abrupt acceleration clears straight motion");
        velocity(filter,time+SECOND,.1,0);
        last=raw;
        raw=fix("gps",time+SECOND,10,27,6);
        check(filter.apply(last,raw,time+SECOND)==raw,"rest bypasses the moving filter");
        velocity(filter,time+2*SECOND,5,0);
        last=raw;
        raw=fix("gps",time+2*SECOND,-10,32,6);
        check(filter.apply(last,raw,time+2*SECOND)==raw,"first fix during a new start is raw");
    }

    private static void bypassStaleMotionAndCorrections() {
        GroundTrackFilter filter=new GroundTrackFilter();
        Selector.Fix last=established(filter);
        long time=last.elapsed+SECOND;
        Selector.Fix raw=fix("gps",time,-10,25,6);
        check(filter.apply(last,raw,time+SECOND)==raw,"no recent velocity during reception");
        filter=new GroundTrackFilter();
        last=established(filter);
        filter.observeVelocity(time,Double.NaN,.2,0,2,time);
        check(filter.apply(last,raw,time)==raw,"missing speed clears evidence");
        filter=new GroundTrackFilter();
        last=established(filter);
        filter.observeVelocity(time,5,.2,0,20,time);
        check(filter.apply(last,raw,time)==raw,"unreliable course clears evidence");
        filter=new GroundTrackFilter();
        last=established(filter);
        velocity(filter,time-SECOND,5,0);
        check(filter.apply(last,raw,time)==raw,"duplicate velocity timing clears evidence");
        filter=new GroundTrackFilter();
        last=established(filter);
        filter.observeVelocity(time+SECOND,5,.2,0,2,time);
        check(filter.apply(last,raw,time)==raw,"future velocity cannot filter a measured point");
        filter=new GroundTrackFilter();
        last=established(filter);
        velocity(filter,time,5,0);
        Selector.Fix correction=fix("gps",time,40,25,6);
        check(filter.apply(last,correction,time)==correction,"large lateral correction passes immediately");
        filter=new GroundTrackFilter();
        last=established(filter);
        velocity(filter,time,5,0);
        Selector.Fix switched=fix("fused",time,-10,25,6);
        check(filter.apply(last,switched,time)==switched,"source switch passes the raw fix");
        velocity(filter,time+SECOND,5,0);
        raw=fix("fused",time+SECOND,10,30,6);
        check(filter.apply(switched,raw,time+SECOND)==raw,"replacement source needs new evidence");
    }

    public static void main(String[] args) {
        straight();
        preserveObliqueAlongTrack();
        bypassMotionChanges();
        bypassStaleMotionAndCorrections();
        System.out.println("Ground track tests passed");
    }
}
