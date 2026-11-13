package org.locationbridge.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Private target of a one-shot Termux result capability. */
public final class TermuxResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { TermuxBridge.receive(context,intent); }
}
