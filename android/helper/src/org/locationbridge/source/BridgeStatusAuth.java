package org.locationbridge.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Pure validation for authenticated bridge status; never controls the bridge. */
final class BridgeStatusAuth {
    private static final Set<String> FIELDS=new HashSet<>(Arrays.asList("protocol","run_id","sent_elapsed_ms","sent_utc_ms","phase","transport","mode","connected","worker_state","source","accuracy_m","accuracy_kind","fix_age_ms","last_sequence","clear_pending","issue"));
    static String sign(String token,String payload) throws Exception {
        if (!AuthProtocol.isHex64(token) || payload==null || payload.length()>4096 || !payload.matches("[A-Za-z0-9+/]*={0,2}")) throw new IllegalArgumentException();
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(AuthProtocol.decode(token),"HmacSHA256"));
        byte[] bytes=mac.doFinal(("bridge_status\n"+payload).getBytes(StandardCharsets.US_ASCII));
        StringBuilder out=new StringBuilder();
        for(byte b:bytes) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    static boolean verify(String token,String payload,String signature) {
        try { return AuthProtocol.isHex64(signature) && MessageDigest.isEqual(AuthProtocol.decode(signature),AuthProtocol.decode(sign(token,payload))); }
        catch(Exception e) { return false; }
    }
    private static boolean integer(Object v,long min) { return (v instanceof Integer || v instanceof Long) && ((Number)v).longValue()>=min; }
    private static boolean number(Object v,double max) { if(v==null)return true; if(!(v instanceof Number))return false; double x=((Number)v).doubleValue();return Double.isFinite(x)&&x>=0&&x<=max; }
    private static boolean choice(Object value,String... allowed) { return value instanceof String && Arrays.asList(allowed).contains(value); }
    static boolean validReport(Map<String,Object> m) {
        if(m==null || !m.keySet().equals(FIELDS) || !integer(m.get("protocol"),1) || ((Number)m.get("protocol")).longValue()!=1
                || !(m.get("run_id") instanceof String) || !((String)m.get("run_id")).matches("none|run-[0-9a-f]{32}")
                || !integer(m.get("sent_elapsed_ms"),1) || !integer(m.get("sent_utc_ms"),1)
                || !choice(m.get("phase"),"not_started","connecting","connected","reconnecting","stopping","stopped","error","unknown")
                || !choice(m.get("transport"),"usb","wifi","none") || !choice(m.get("mode"),"gps","hybrid","fusion","test","none")
                || !choice(m.get("worker_state"),"waiting","running","stale","reconnecting","stopped","error","unknown")
                || !choice(m.get("source"),"gps","network","fused","test","inertial","ble_lns","unknown")
                || !choice(m.get("accuracy_kind"),"reported","model_estimate","unknown")
                || !choice(m.get("issue"),"none","waiting_usb","usb_missing","usb_role","usb_permission","usb_role_failed","usb_busy","waiting_helper","source_unavailable","worker_error","clear_unconfirmed","bridge_error","worker_stale")
                || !number(m.get("accuracy_m"),Double.MAX_VALUE) || !number(m.get("fix_age_ms"),1e12)
                || m.get("last_sequence")!=null && !integer(m.get("last_sequence"),0)
                || m.get("clear_pending")!=null && !(m.get("clear_pending") instanceof Boolean)
                || m.get("connected")!=null && !(m.get("connected") instanceof Boolean)) return false;
        String phase=(String)m.get("phase"); Object connected=m.get("connected");
        return ("connected".equals(phase) == Boolean.TRUE.equals(connected))
                && (!("stopped".equals(phase)||"not_started".equals(phase)) || Boolean.FALSE.equals(connected));
    }
    static boolean acceptTime(long sent,long sentUtc,long now,long nowUtc,long previous,long previousReceipt) {
        if(sent<=0 || sentUtc<=0 || now<sent || now-sent>10000 || sentUtc>nowUtc+2000 || nowUtc-sentUtc>10000) return false;
        return previousReceipt>now || previous<=0 || sent>previous;
    }
    static boolean fresh(long received,long receivedUtc,long now,long nowUtc) {
        if(received<=0 || receivedUtc<=0 || now<received || nowUtc<receivedUtc-2000) return false;
        long elapsed=now-received, wall=nowUtc-receivedUtc;
        return elapsed<=8000 && Math.abs(wall-elapsed)<=2000;
    }
}
