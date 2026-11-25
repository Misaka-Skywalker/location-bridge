package org.locationbridge.source;
public final class DisplayUnitsTest {
    static void check(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args){
        check(DisplayUnits.altitude(Double.NaN).equals("---"));
        check(DisplayUnits.altitude(-30.48).equals("-30.5 m\n-100 ft"));
        check(DisplayUnits.speed(10).equals("36.0 km/h\n19.4 kts"));
        check(DisplayUnits.speed(1852.0/3600).equals("1.9 km/h\n1.0 kts"));
        check(DisplayUnits.speed(0).equals("0.0 km/h\n0.0 kts"));
        check(DisplayUnits.speed(Double.NaN).equals("---"));
        check(DisplayUnits.speed(-1).equals("---"));
        check(DisplayUnits.distance(-1500,true).equals("-1.50 km"));
        check(DisplayUnits.correctionDistance(18.5,false).equals("18.5 m / 0.01 NM"));
        check(DisplayUnits.correctionDistance(-1852,true).equals("-1.85 km / -1.00 NM"));
        check(DisplayUnits.correctionDistance(0,true).equals("+0.0 m / +0.00 NM"));
        check(DisplayUnits.correctionDistance(Double.NaN,false).equals("---"));
        check(DisplayUnits.correctionDistance(Double.POSITIVE_INFINITY,true).equals("---"));
        check(DisplayUnits.duration(7200).equals("2.00 h"));
        System.out.println("DisplayUnits tests passed");
    }
}
