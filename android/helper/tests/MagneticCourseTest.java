package org.locationbridge.source;

public final class MagneticCourseTest {
    static void near(double expected,Double actual) {
        if(actual==null || !Double.isFinite(actual) || Math.abs(expected-actual)>1e-9) throw new AssertionError(expected+" != "+actual);
    }
    public static void main(String[] args) {
        near(80,MagneticCourse.fromTrue(90,10));
        near(100,MagneticCourse.fromTrue(90,-10));
        near(355,MagneticCourse.fromTrue(5,10));
        near(5,MagneticCourse.fromTrue(355,-10));
        near(0,MagneticCourse.fromTrue(0,0));
        near(180,MagneticCourse.fromTrue(0,180));
        near(180,MagneticCourse.fromTrue(0,-180));
        near(0,MagneticCourse.fromTrue(10,10));
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,360})
            if(MagneticCourse.fromTrue(invalid,0)!=null) throw new AssertionError("invalid true course accepted");
        for(double invalid:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,-181,181})
            if(MagneticCourse.fromTrue(90,invalid)!=null) throw new AssertionError("invalid declination accepted");
        System.out.println("MagneticCourse tests passed");
    }
}
