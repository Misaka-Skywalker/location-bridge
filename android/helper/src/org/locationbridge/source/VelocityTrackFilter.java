package org.locationbridge.source;

import java.util.ArrayDeque;

/** Smooths position residuals after compensating for measured ground motion. */
final class VelocityTrackFilter {
    private static final long MAX_GAP=1_500_000_000L;
    private static final double SECOND=1e9, TIME_CONSTANT=2.5;
    private final ArrayDeque<Velocity> history=new ArrayDeque<>();

    private static final class Velocity {
        final long time;
        final double east,north,error;
        Velocity(long time,double speed,double speedError,double course,double courseError) {
            this.time=time;
            double angle=Math.toRadians(course);
            east=speed*Math.sin(angle);
            north=speed*Math.cos(angle);
            error=Math.hypot(speedError,speed*Math.toRadians(courseError));
        }
    }

    void reset() { history.clear(); }

    void observe(long time,long now,double speed,double speedError,double course,double courseError) {
        if (time<=0 || time>now || now-time>MAX_GAP || !(speedError>=0 && speedError<=2)
                || !(speed-2*speedError>=1 && speed<=450) || !(course>=0 && course<360)
                || !(courseError>=0 && courseError<=10)) {
            reset();
            return;
        }
        Velocity next=new Velocity(time,speed,speedError,course,courseError);
        Velocity previous=history.peekLast();
        if (previous!=null) {
            double dt=(time-previous.time)/SECOND;
            double change=Math.hypot(next.east-previous.east,next.north-previous.north);
            if (dt<=0 || time-previous.time>MAX_GAP || change>15*dt+2*(previous.error+next.error)) reset();
        }
        history.addLast(next);
        while (history.size()>64 || time-history.getFirst().time>3*MAX_GAP) history.removeFirst();
    }

    // Null means velocity doesn't cover the interval. The low-speed cross-track filter
    // can still use its own straight-course checks. A raw return explicitly accepts a fix.
    Selector.Fix apply(Selector.Fix last,Selector.Fix raw,long now) {
        if (last==null || !raw.source.equals(last.source)
                || !("gps".equals(raw.source) || "fused".equals(raw.source))
                || raw.elapsed<=last.elapsed || raw.elapsed-last.elapsed>MAX_GAP || now-raw.elapsed>MAX_GAP
                || !(raw.accuracy>0 && raw.accuracy<=30) || last.accuracy>Math.min(30,2*raw.accuracy+3)
                || Math.abs(raw.latitude)>=85 || Math.abs(last.latitude)>=85) return raw;
        if (history.size()<2 || history.getFirst().time>last.elapsed || history.getLast().time<raw.elapsed) return null;

        double east=0,north=0,travelError=0;
        Velocity a=null;
        for (Velocity b:history) {
            if (a!=null && b.time>last.elapsed) {
                // Integrate the linearly interpolated velocity only between the two fix timestamps.
                long start=Math.max(a.time,last.elapsed),end=Math.min(b.time,raw.elapsed);
                double fraction=((start-a.time)+(end-start)*.5)/(b.time-a.time);
                double dt=(end-start)/SECOND;
                east+=(a.east+(b.east-a.east)*fraction)*dt;
                north+=(a.north+(b.north-a.north)*fraction)*dt;
                travelError+=Math.max(a.error,b.error)*dt;
            }
            if (b.time>=raw.elapsed) break;
            a=b;
        }
        if (travelError>=raw.accuracy) return raw;

        double[] predicted=DeadReckoning.offset(last.latitude,last.longitude,east,north);
        double dLat=predicted[0]-raw.latitude,dLon=Math.IEEEremainder(predicted[1]-raw.longitude,360);
        double residual=111320*Math.hypot(dLat,dLon*Math.cos(Math.toRadians(raw.latitude)));
        if (residual<.01 || residual>Math.min(35,Math.max(22,2*(last.accuracy+raw.accuracy)))) return raw;
        double dt=(raw.elapsed-last.elapsed)/SECOND;
        double relativeError=travelError/raw.accuracy;
        double weight=Math.exp(-dt/TIME_CONSTANT)/(1+relativeError*relativeError);
        weight=Math.min(weight,Math.min(8,1.5*raw.accuracy)/residual);
        return new Selector.Fix(raw.source,raw.latitude+weight*dLat,Math.IEEEremainder(raw.longitude+weight*dLon,360),
                raw.accuracy,raw.elapsed,raw.mock,raw.timestamp,raw.sequence);
    }
}
