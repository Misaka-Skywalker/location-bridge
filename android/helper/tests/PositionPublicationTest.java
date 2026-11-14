package org.locationbridge.source;

public final class PositionPublicationTest {
    static final long S=1_000_000_000L;
    static void check(boolean value,String reason) { if(!value) throw new AssertionError(reason); }
    static Selector.Fix fix(long t) { return new Selector.Fix("gps",30,104,5,t,false,1000,1); }
    public static void main(String[] args) {
        long t=100*S;
        PositionPublication publication=new PositionPublication();
        Selector.Fix gps=fix(t);
        PositionPublication.Output first=publication.select(gps,null,t);
        check(first!=null && first.measured==gps,"fresh measured fix");
        check(publication.select(gps,null,t+S).sequence==first.sequence,"poll cannot mint fresh sequence");
        DeadReckoning dr=new DeadReckoning();
        check(dr.anchor(gps,30,0.5,90,1,t),"anchor");
        for(int i=1;i<=40;i++) dr.acceleration(t+i*S/20,0,0,t+i*S/20);
        DeadReckoning.Prediction prediction=dr.predict(t+2*S);
        check(prediction!=null,"prediction ready");
        check(publication.select(gps,prediction,t+2*S).measured==gps,"valid measured source wins over INS");
        PositionPublication.Output estimated=publication.select(null,prediction,t+2*S);
        check(estimated.prediction!=null && estimated.sequence>first.sequence,"prediction takes over without measured source");
        check(publication.select(fix(t+S),null,t+2*S)==null,"reacquisition cannot publish older time");
        check(publication.select(gps,null,t+3*S)==null,"expiry never revives older raw fix");
        Selector.Fix returned=fix(t+3*S);
        check(publication.select(returned,null,t+3*S).measured==returned,"newer GNSS takes over");
        check(publication.select(null,prediction,t+3*S)==null,"expired prediction rejected defensively");
        check(publication.select(returned,null,t+14*S)==null,"stale raw rejected defensively");
        System.out.println("PositionPublication tests passed");
    }
}
