package org.locationbridge.source;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;
import java.security.SecureRandom;

/** Launches only fixed bridge commands through the public Termux service. */
final class TermuxBridge {
    public static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String PREFS = "termux_command_state";
    private static final Object LOCK = new Object();
    private static final ComponentName SERVICE = new ComponentName("com.termux", "com.termux.app.RunCommandService");
    private static final String ACTION = "com.termux.RUN_COMMAND";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE); }
    private static TermuxLaunchState readSlot(SharedPreferences p,String prefix) {
        return new TermuxLaunchState(p.getString(prefix+"state","none"),p.getLong(prefix+"issued",0),
                p.getString(prefix+"operation",null),p.getString(prefix+"nonce",null),p.getString(prefix+"error",null));
    }
    private static TermuxLaunchSlots read(SharedPreferences p) {
        TermuxLaunchSlots slots=new TermuxLaunchSlots();
        slots.start=readSlot(p,"start_"); slots.auxiliary=readSlot(p,"aux_");
        slots.latest=p.getString("latest","none"); return slots;
    }
    private static void writeSlot(SharedPreferences.Editor e,String prefix,TermuxLaunchState s) {
        e.putString(prefix+"state",s.state).putLong(prefix+"issued",s.issued).putString(prefix+"operation",s.operation)
                .putString(prefix+"nonce",s.nonce).putString(prefix+"error",s.error);
    }
    private static void write(SharedPreferences p,TermuxLaunchSlots slots) {
        SharedPreferences.Editor e=p.edit().putString("latest",slots.latest);
        writeSlot(e,"start_",slots.start); writeSlot(e,"aux_",slots.auxiliary); e.commit();
    }
    private static Intent resultIntent(Context context,String id) {
        return new Intent(context,TermuxResultReceiver.class).setData(Uri.parse("locationbridge-termux://result/" + id));
    }
    private static void cancel(Context context,TermuxLaunchState s) {
        if (s.nonce == null) return;
        PendingIntent old=PendingIntent.getBroadcast(context,0,resultIntent(context,s.nonce),
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (old != null) old.cancel();
    }
    private static String nonce() {
        byte[] bytes=new byte[16]; RANDOM.nextBytes(bytes);
        StringBuilder out=new StringBuilder(32);
        for (byte value:bytes) out.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
        return out.toString();
    }
    static String launch(Context context, TermuxCommand command) {
        if (command == null) throw new IllegalArgumentException("command required");
        synchronized (LOCK) {
            SharedPreferences p=prefs(context); long now=SystemClock.elapsedRealtime();
            TermuxLaunchSlots slots=read(p);
            if (slots.busy(command,now)) return "busy";
            try { context.getPackageManager().getServiceInfo(SERVICE,0); }
            catch (PackageManager.NameNotFoundException e) { return "missing_termux"; }
            if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return "permission_required";
            String id=nonce();
            Intent result=resultIntent(context,id);
            PendingIntent callback=PendingIntent.getBroadcast(context,0,result,PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE);
            Intent request=new Intent(ACTION).setComponent(SERVICE)
                    .putExtra("com.termux.RUN_COMMAND_PATH",TermuxCommand.SCRIPT)
                    .putExtra("com.termux.RUN_COMMAND_ARGUMENTS",command.arguments())
                    .putExtra("com.termux.RUN_COMMAND_WORKDIR","/data/data/com.termux/files/home")
                    .putExtra("com.termux.RUN_COMMAND_BACKGROUND",false)
                    .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION","0")
                    .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",callback);
            cancel(context,slots.slot(command));
            slots.issue(command,now,id); write(p,slots);
            try {
                if (context.startService(request) == null) throw new IllegalStateException("service unavailable");
            } catch (RuntimeException e) {
                callback.cancel();
                TermuxLaunchState failed=new TermuxLaunchState("failed",now,command.operation(),null,"launch_failed");
                if (command.isStart()) slots.start=failed; else slots.auxiliary=failed;
                write(p,slots); return "launch_failed";
            }
            try {
                Intent terminal=context.getPackageManager().getLaunchIntentForPackage("com.termux");
                if (terminal == null) throw new IllegalStateException("terminal unavailable");
                if (!(context instanceof android.app.Activity)) terminal.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(terminal);
            } catch (RuntimeException e) {
                TermuxLaunchState failedOpen=new TermuxLaunchState("pending",now,command.operation(),id,"terminal_open_failed");
                if (command.isStart()) slots.start=failedOpen; else slots.auxiliary=failedOpen;
                write(p,slots);
            }
            return null;
        }
    }
    static JSONObject snapshot(Context context) {
        synchronized (LOCK) {
            TermuxLaunchState s=read(prefs(context)).display();
            try {
                return new JSONObject().put("state",s.view(SystemClock.elapsedRealtime()))
                        .put("issued_elapsed_ms",s.issued == 0 ? JSONObject.NULL : s.issued)
                        .put("operation",s.operation == null ? JSONObject.NULL : s.operation)
                        .put("error",s.error == null ? JSONObject.NULL : s.error);
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    static void receive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri uri=intent.getData();
        if (!"locationbridge-termux".equals(uri.getScheme()) || !"result".equals(uri.getHost()) || uri.getPathSegments().size()!=1) return;
        String id=uri.getLastPathSegment();
        synchronized (LOCK) {
            SharedPreferences p=prefs(context); TermuxLaunchSlots slots=read(p);
            Integer err=null, exit=null;
            try {
                Bundle result=intent.getBundleExtra("result");
                if (result != null && result.containsKey("err") && result.get("err") instanceof Integer) err=result.getInt("err");
                if (result != null && result.containsKey("exitCode") && result.get("exitCode") instanceof Integer) exit=result.getInt("exitCode");
            } catch (RuntimeException ignored) { err=null; exit=null; }
            if (slots.receive(id,SystemClock.elapsedRealtime(),err,exit)) write(p,slots);
        }
    }
}
