package org.locationbridge.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Authenticated status display only; broadcasts never change a service's state. */
public final class BridgeStatusReceiver extends BroadcastReceiver {
    private static final String ACTION="org.locationbridge.source.BRIDGE_STATUS";
    private static final String PREFS="bridge_status";
    private static Map<String,Object> fields(JSONObject json) throws Exception {
        Map<String,Object> out=new HashMap<>();
        Iterator<String> keys=json.keys();
        while(keys.hasNext()) { String key=keys.next(); Object value=json.get(key); out.put(key,value==JSONObject.NULL?null:value); }
        return out;
    }
    private static JSONObject decode(String encoded) throws Exception {
        byte[] bytes=Base64.getDecoder().decode(encoded);
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        JSONTokener tokener=new JSONTokener(text);
        Object obj=tokener.nextValue();
        if(!(obj instanceof JSONObject) || tokener.nextClean()!=0) throw new IllegalArgumentException();
        return (JSONObject)obj;
    }
    @Override public void onReceive(Context context,Intent intent) {
        if(intent==null || !ACTION.equals(intent.getAction())) return;
        try {
            String payload=intent.getStringExtra("payload"), signature=intent.getStringExtra("signature");
            String key=context.getSharedPreferences(MainActivity.PREFS,Context.MODE_PRIVATE).getString(MainActivity.TOKEN,null);
            if(!BridgeStatusAuth.verify(key,payload,signature)) return;
            JSONObject json=decode(payload); Map<String,Object> report=fields(json);
            if(!BridgeStatusAuth.validReport(report)) return;
            synchronized(BridgeStatusReceiver.class) {
                long now=SystemClock.elapsedRealtime(), utc=System.currentTimeMillis();
                SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
                long sent=((Number)report.get("sent_elapsed_ms")).longValue(), sentUtc=((Number)report.get("sent_utc_ms")).longValue();
                if(!BridgeStatusAuth.acceptTime(sent,sentUtc,now,utc,prefs.getLong("last_sent",0),prefs.getLong("received_elapsed",0))) return;
                prefs.edit().putString("payload",payload).putLong("last_sent",sent).putLong("received_elapsed",now).putLong("received_utc",utc).commit();
            }
        } catch(Exception ignored) { }
    }
    static JSONObject snapshot(Context context) {
        synchronized(BridgeStatusReceiver.class) {
            try {
                SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
                String payload=prefs.getString("payload",null);
                if(payload==null) return unknown();
                JSONObject json=decode(payload);
                Map<String,Object> report=fields(json);
                if(!BridgeStatusAuth.validReport(report)) return unknown();
                long now=SystemClock.elapsedRealtime(),utc=System.currentTimeMillis();
                long received=prefs.getLong("received_elapsed",0),receivedUtc=prefs.getLong("received_utc",0);
                long sent=((Number)report.get("sent_elapsed_ms")).longValue();
                boolean sameBoot=received>0 && now>=received && Math.abs((utc-receivedUtc)-(now-received))<=2000;
                long age=sameBoot && now>=sent ? now-sent : -1;
                boolean fresh=BridgeStatusAuth.fresh(received,receivedUtc,now,utc) && age>=0 && age<=8000;
                json.put("reported_connected",json.get("connected"));
                if(!fresh) json.put("connected",JSONObject.NULL);
                return json.put("fresh",fresh).put("report_age_ms",age<0?JSONObject.NULL:age);
            } catch(Exception ignored) { return unknown(); }
        }
    }
    private static JSONObject unknown() {
        try { return new JSONObject().put("phase","unknown").put("issue","none").put("fresh",false)
                .put("connected",JSONObject.NULL).put("reported_connected",JSONObject.NULL).put("report_age_ms",JSONObject.NULL); }
        catch(Exception impossible) { return new JSONObject(); }
    }
}
