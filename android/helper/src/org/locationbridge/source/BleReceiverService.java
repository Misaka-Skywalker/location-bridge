package org.locationbridge.source;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/** Selected BLE position receiver. GATT lifecycle and queue are serialized on the main looper. */
public final class BleReceiverService extends Service {
    static final String CONNECT = "org.locationbridge.source.BLE_CONNECT";
    static final String STOP = "org.locationbridge.source.BLE_STOP";
    private static final UUID LNS = UUID.fromString("00001819-0000-1000-8000-00805f9b34fb");
    private static final UUID LOCATION_SPEED = UUID.fromString("00002a67-0000-1000-8000-00805f9b34fb");
    private static final UUID NAVIGATION = UUID.fromString("00002a68-0000-1000-8000-00805f9b34fb");
    private static final UUID POSITION_QUALITY = UUID.fromString("00002a69-0000-1000-8000-00805f9b34fb");
    private static final UUID LN_FEATURE = UUID.fromString("00002a6a-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static volatile BleReceiverService instance;
    private static final String USE_PREFS = "external_location_use", MANUAL_EXCLUDED = "manual_excluded";
    private ExternalUseGate useGate;
    private ExternalPositionStore positions;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object dataLock = new Object();
    private BluetoothGatt gatt;
    private int generation;
    private String state = "stopped", receiverId, lastCharacteristic, lastPayload;
    private boolean connected;
    private int negotiatedMtu = 23;
    private long packets, lastNotification;
    private String lastLocationHex, lastQualityHex;
    private String lastNavigationHex, lastFeatureHex;
    private long navigationPackets, navigationReceivedElapsedNanos;
    private String lastTxt;
    private long txtPackets, txtReceivedElapsedNanos;
    private long locationSequence, locationReceivedElapsedNanos, locationReceivedTimestampMs, qualityReceivedElapsedNanos;
    private boolean inventoryTruncated;
    // Identity, not UUID: duplicate service/characteristic UUIDs are valid.
    private final Map<BluetoothGattCharacteristic, JSONObject> inventory = new IdentityHashMap<>();
    private final Set<BluetoothGattCharacteristic> subscriptions = Collections.newSetFromMap(new IdentityHashMap<BluetoothGattCharacteristic, Boolean>());
    private boolean locationSupported, locationSubscribed, locationReceived, subscriptionFailed;
    private JSONArray services = new JSONArray();
    private final ArrayDeque<Op> operations = new ArrayDeque<>();
    private Op active;
    private long opSerial;
    private static final class Op {
        final BluetoothGattCharacteristic ch; final boolean subscribe;
        Op(BluetoothGattCharacteristic c, boolean s) { ch=c; subscribe=s; }
    }
    @Override public void onCreate() {
        super.onCreate(); Language.init(this);
        useGate = new ExternalUseGate(manualExcluded(this));
        positions = new ExternalPositionStore(useGate);
        instance = this;
    }
    static boolean manualExcluded(Context context) {
        return context.getSharedPreferences(USE_PREFS, MODE_PRIVATE).getBoolean(MANUAL_EXCLUDED, false);
    }
    /** Changes candidate eligibility only; leaves GATT, subscriptions and reception running. */
    static boolean setManualExcluded(Context context, boolean excluded) {
        if (!context.getSharedPreferences(USE_PREFS, MODE_PRIVATE).edit().putBoolean(MANUAL_EXCLUDED, excluded).commit()) return false;
        BleReceiverService service=instance;
        if (service != null) synchronized(service.dataLock) { service.useGate.setManualExcluded(excluded,SystemClock.elapsedRealtimeNanos()); }
        return true;
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (!CONNECT.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        // Foreground notification is required before starting asynchronous work.
        try { foreground(); } catch (RuntimeException e) { stopSelf(); return START_NOT_STICKY; }
        String address = intent.getStringExtra("address");
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) { stopSelf(); return START_NOT_STICKY; }
        connect(address);
        return START_NOT_STICKY;
    }
    static void refreshNotification() { if (instance != null) instance.foreground(); }
    private void foreground() {
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("ble_receiver", Language.tr("蓝牙定位接收"), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 42, new Intent(this,BleReceiverActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 42, new Intent(this,BleReceiverService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this,"ble_receiver").setContentTitle(Language.tr("蓝牙定位接收运行中"))
                .setContentText(Language.tr("点击查看状态，或停止接收")) .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,Language.tr("停止"),stop).build()).build();
        startForeground(42,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }
    private void setState(String value, boolean isConnected) { synchronized(dataLock) { state=value; connected=isConnected; } }
    private void resetData() {
        synchronized(dataLock) {
            useGate.resetSession(); positions.clear(); lastTxt=null; txtPackets=0; txtReceivedElapsedNanos=0;
            lastNavigationHex=null; lastFeatureHex=null; navigationPackets=0; navigationReceivedElapsedNanos=0;
            receiverId=null; packets=0; lastNotification=0; lastCharacteristic=null; lastPayload=null; services=new JSONArray(); connected=false; negotiatedMtu=23; lastLocationHex=null; lastQualityHex=null; locationSequence=0; locationReceivedElapsedNanos=0; locationReceivedTimestampMs=0; qualityReceivedElapsedNanos=0; inventoryTruncated=false; inventory.clear(); subscriptions.clear(); locationSupported=false; locationSubscribed=false; locationReceived=false; subscriptionFailed=false;
        }
    }
    private void closeConnection() {
        generation++; opSerial++; operations.clear(); active=null;
        BluetoothGatt old=gatt; gatt=null;
        if (old != null) {
            try { old.disconnect(); } catch (SecurityException ignored) { }
            try { old.close(); } catch (SecurityException ignored) { }
        }
    }
    private void connect(String address) {
        closeConnection(); resetData();
        final int epoch = generation;
        synchronized(dataLock) { receiverId=UUID.randomUUID().toString(); state="connecting"; }
        try {
            BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter adapter=manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) { fail("bluetooth_unavailable"); return; }
            BluetoothDevice device=adapter.getRemoteDevice(address);
            gatt=device.connectGatt(this,false,callback(epoch),BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { fail("connect_failed"); return; }
            main.postDelayed(() -> { if (epoch == generation && !isConnected()) { fail("connect_timeout"); } },20000);
        } catch (IllegalArgumentException | SecurityException e) { fail("connect_failed"); }
    }
    private void fail(String reason) { closeConnection(); resetData(); setState(reason,false); }
    private boolean isConnected() { synchronized(dataLock) { return connected; } }
    private boolean valid(int epoch, BluetoothGatt candidate) { return epoch == generation && candidate != null && candidate == gatt; }
    private BluetoothGattCallback callback(final int epoch) {
        return new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt g, int code, int newState) {
                main.post(() -> {
                    if (!valid(epoch,g)) return;
                    if (code == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                        setState("negotiating_mtu",true);
                        try {
                            if (!g.requestMtu(517)) { discover(epoch,g); return; }
                            main.postDelayed(() -> { if (valid(epoch,g) && isState("negotiating_mtu")) { fail("mtu_timeout"); } },10000);
                        } catch (SecurityException e) { fail("permission_error"); }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED || code != BluetoothGatt.GATT_SUCCESS) {
                        closeConnection(); resetData(); setState("disconnected",false);
                    }
                });
            }
            @Override public void onMtuChanged(BluetoothGatt g, int mtu, int code) {
                main.post(() -> {
                    if (!valid(epoch,g)) return;
                    if (code == BluetoothGatt.GATT_SUCCESS && mtu >= 23 && mtu <= 517)
                        synchronized(dataLock) { negotiatedMtu=mtu; }
                    if (isState("negotiating_mtu")) discover(epoch,g);
                });
            }
            @Override public void onServicesDiscovered(BluetoothGatt g, int code) { main.post(() -> discovered(epoch,g,code)); }
            @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int code) { main.post(() -> descriptorWritten(epoch,g,d,code)); }
            @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int code) {
                if (Build.VERSION.SDK_INT < 33) { byte[] v=c.getValue(); postRead(epoch,g,c,v,code); }
            }
            @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int code) { postRead(epoch,g,c,value,code); }
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                if (Build.VERSION.SDK_INT < 33) { byte[] v=c.getValue(); postValue(epoch,g,c,v); }
            }
            @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) { postValue(epoch,g,c,value); }
        };
    }
    private boolean isState(String expected) { synchronized(dataLock) { return expected.equals(state); } }
    private void discover(int epoch, BluetoothGatt g) {
        if (!valid(epoch,g)) return;
        setState("discovering",true);
        try {
            if (!g.discoverServices()) { fail("discovery_failed"); return; }
            main.postDelayed(() -> { if (valid(epoch,g) && isState("discovering")) fail("discovery_timeout"); },15000);
        } catch (SecurityException e) { fail("permission_error"); }
    }
    private void discovered(int epoch, BluetoothGatt g, int code) {
        if (!valid(epoch,g)) return;
        if (code != BluetoothGatt.GATT_SUCCESS) { fail("discovery_failed"); return; }
        JSONArray result = new JSONArray();
        try {
            List<BluetoothGattService> found = new ArrayList<>(g.getServices());
            int count = 0; boolean truncated = false;
            for (BluetoothGattService service : found) {
                JSONArray characteristics = new JSONArray();
                boolean includeService = result.length() < 20;
                if (!includeService) truncated = true;
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    if (includeService && count < 60) {
                        JSONObject entry = new JSONObject().put("uuid",c.getUuid().toString()).put("instance_id",c.getInstanceId()).put("properties",c.getProperties()).put("subscribed",false);
                        characteristics.put(entry);
                        synchronized(dataLock) { inventory.put(c, entry); }
                        count++;
                    } else truncated = true;
                    if (!LNS.equals(service.getUuid())) continue;
                    UUID id=c.getUuid(); int p=c.getProperties();
                    if (LOCATION_SPEED.equals(id) && (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) locationSupported = true;
                    if ((POSITION_QUALITY.equals(id) || LN_FEATURE.equals(id)) && (p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) operations.add(new Op(c,false));
                    if ((LOCATION_SPEED.equals(id) || NAVIGATION.equals(id) || POSITION_QUALITY.equals(id)) && (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) operations.add(new Op(c,true));
                }
                if (includeService) result.put(new JSONObject().put("uuid",service.getUuid().toString()).put("instance_id",service.getInstanceId()).put("characteristics",characteristics));
            }
            synchronized(dataLock) { services=result; inventoryTruncated=truncated; state="subscribing"; }
            next(epoch,g);
        } catch (Exception e) { fail("discovery_failed"); }
    }
    private void subscriptionResult(BluetoothGattCharacteristic c, boolean success, String error) {
        synchronized(dataLock) {
            if (success) {
                subscriptions.add(c);
                if (LOCATION_SPEED.equals(c.getUuid())) locationSubscribed = true;
            } else subscriptionFailed = true;
            JSONObject entry = inventory.get(c);
            if (entry != null) try {
                entry.put("subscribed", success);
                entry.put("subscription_error", success ? JSONObject.NULL : error);
            } catch (Exception ignored) { }
        }
    }
    private void updateReceiveState() {
        synchronized(dataLock) {
            state = subscriptionFailed ? "subscription_failed" : !locationSupported ? "unsupported"
                    : locationReceived ? "receiving" : locationSubscribed ? "subscribed" : "subscription_failed";
            connected = true;
        }
    }
    private void next(int epoch, BluetoothGatt g) {
        if (!valid(epoch,g) || active != null) return;
        active=operations.poll();
        if (active == null) { updateReceiveState(); return; }
        final Op op=active; final long serial=++opSerial;
        try {
            boolean accepted;
            if (op.subscribe) {
                BluetoothGattDescriptor descriptor=op.ch.getDescriptor(CCCD);
                if (descriptor == null || !g.setCharacteristicNotification(op.ch,true)) accepted=false;
                else {
                    byte[] bytes = (op.ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                    if (Build.VERSION.SDK_INT >= 33) accepted=g.writeDescriptor(descriptor,bytes)==BluetoothStatusCodes.SUCCESS;
                    else { descriptor.setValue(bytes); accepted=g.writeDescriptor(descriptor); }
                }
            } else accepted=g.readCharacteristic(op.ch);
            if (!accepted) {
                if (op.subscribe) subscriptionResult(op.ch, false, "start_failed");
                active=null; next(epoch,g); return;
            }
            main.postDelayed(() -> { if (valid(epoch,g) && active == op && opSerial == serial) { fail("operation_timeout"); } },10000);
        } catch (SecurityException e) { fail("permission_error"); }
    }
    private void descriptorWritten(int epoch, BluetoothGatt g, BluetoothGattDescriptor d, int code) {
        if (!valid(epoch,g) || active == null || !active.subscribe || d.getCharacteristic() != active.ch || !CCCD.equals(d.getUuid())) return;
        subscriptionResult(active.ch, code == BluetoothGatt.GATT_SUCCESS, "cccd_write_failed");
        active=null; next(epoch,g);
    }
    private void postRead(int epoch, BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int code) {
        byte[] copy=value == null || value.length > 128 ? null : Arrays.copyOf(value,value.length);
        final long received = SystemClock.elapsedRealtimeNanos(), wall = System.currentTimeMillis();
        main.post(() -> {
            if (!valid(epoch,g) || active == null || active.subscribe || active.ch != c) return;
            if (code == BluetoothGatt.GATT_SUCCESS && copy != null) recordValue(c.getUuid(),copy,false,received,wall);
            active=null; next(epoch,g);
        });
    }
    private void postValue(int epoch, BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
        byte[] copy=value == null || value.length > 128 ? null : Arrays.copyOf(value,value.length);
        final long received = SystemClock.elapsedRealtimeNanos(), wall = System.currentTimeMillis();
        main.post(() -> {
            if (!valid(epoch,g) || copy == null || c.getService() == null || !LNS.equals(c.getService().getUuid())) return;
            UUID id=c.getUuid(); if (!LOCATION_SPEED.equals(id) && !NAVIGATION.equals(id) && !POSITION_QUALITY.equals(id)) return;
            recordValue(id,copy,true,received,wall);
            synchronized(dataLock) {
                if (LOCATION_SPEED.equals(id) && subscriptions.contains(c)) locationReceived = true;
            }
            // Don't claim receiving before the subscription is acknowledged and a value arrives.
            if (active == null && operations.isEmpty()) updateReceiveState();
        });
    }
    private void recordValue(UUID id, byte[] value, boolean notification, long received, long wall) {
        synchronized(dataLock) {
            packets++; if (notification) lastNotification=received;
            lastCharacteristic=id.toString();
            StringBuilder hex=new StringBuilder();
            for (int i=0;i<Math.min(value.length,128);i++) hex.append(String.format(Locale.ROOT,"%02x",value[i]&255));
            lastPayload=hex.toString();
            String txt = notification ? NmeaTxt.parse(value) : null;
            if (txt != null) {
                lastTxt=txt; txtPackets++; txtReceivedElapsedNanos=received;
                useGate.onTxt(received);
            } else if (LOCATION_SPEED.equals(id)) {
                lastLocationHex=lastPayload; locationSequence++; locationReceivedElapsedNanos=received; locationReceivedTimestampMs=wall;
                positions.onLocation(value,locationSequence,wall,received);
            } else if (POSITION_QUALITY.equals(id)) {
                lastQualityHex=lastPayload; qualityReceivedElapsedNanos=received;
            } else if (NAVIGATION.equals(id)) {
                lastNavigationHex=lastPayload; navigationPackets++; navigationReceivedElapsedNanos=received;
            } else if (LN_FEATURE.equals(id)) {
                lastFeatureHex=lastPayload;
            }
        }
        handleValue(id,Arrays.copyOf(value,value.length));
    }
    /** Hook for the LNS decoder; receives data only from the selected device's known LNS characteristics. */
    void handleValue(UUID characteristic, byte[] value) { }
    private JSONObject positionSnapshotLocked() throws JSONException {
        long now=SystemClock.elapsedRealtimeNanos(), wall=System.currentTimeMillis();
        String reason=useGate.manualExcluded() ? "manual_excluded" : !connected ? "disconnected" : useGate.reason(now,wall);
        ExternalPositionStore.Candidate candidate=connected ? positions.selected(now,wall) : null;
        return positionEnvelope(state,connected,receiverId,now,reason,useGate.manualExcluded(),useGate.waitingAfterTxt(),
                candidate == null ? JSONObject.NULL : ExternalBleSnapshot.sample(candidate));
    }
    private static JSONObject positionEnvelope(String state,boolean connected,String receiver,long now,String reason,
                                               boolean excluded,boolean waiting,Object sample) throws JSONException {
        return new JSONObject().put("protocol",1).put("kind","external_gnss").put("state",state)
                .put("elapsed_realtime_nanos",now).put("connected",connected)
                .put("receiver_id",receiver == null ? JSONObject.NULL : receiver)
                .put("selection",new JSONObject().put("eligible","eligible".equals(reason)).put("reason",reason)
                        .put("manual_excluded",excluded).put("waiting_after_txt",waiting))
                .put("sample",sample);
    }
    static JSONObject positionSnapshot(Context context) {
        BleReceiverService service=instance;
        if (service!=null) synchronized(service.dataLock) {
            try { return service.positionSnapshotLocked(); }
            catch (JSONException ignored) { }
        }
        return emptyPositionSnapshot(context);
    }
    public static JSONObject snapshot(Context context) {
        BleReceiverService service=instance;
        if (service == null) return emptySnapshot(context);
        synchronized(service.dataLock) {
            try {
                return service.positionSnapshotLocked().put("txt_packets",service.txtPackets)
                        .put("last_txt",service.lastTxt == null ? JSONObject.NULL : service.lastTxt)
                        .put("txt_received_elapsed_nanos",service.txtReceivedElapsedNanos == 0 ? JSONObject.NULL : service.txtReceivedElapsedNanos)
                        .put("mtu",service.negotiatedMtu)
                        .put("services",new JSONArray(service.services.toString())).put("inventory_truncated",service.inventoryTruncated).put("subscription_failed",service.subscriptionFailed).put("packets",service.packets)
                        .put("last_location_hex", service.lastLocationHex == null ? JSONObject.NULL : service.lastLocationHex)
                        .put("location_sequence",service.locationSequence)
                        .put("location_received_elapsed_nanos",service.locationReceivedElapsedNanos == 0 ? JSONObject.NULL : service.locationReceivedElapsedNanos)
                        .put("location_received_timestamp_ms",service.locationReceivedTimestampMs == 0 ? JSONObject.NULL : service.locationReceivedTimestampMs)
                        .put("last_quality_hex",service.lastQualityHex == null ? JSONObject.NULL : service.lastQualityHex)
                        .put("last_navigation_hex",service.lastNavigationHex == null ? JSONObject.NULL : service.lastNavigationHex)
                        .put("navigation_packets",service.navigationPackets)
                        .put("navigation_received_elapsed_nanos",service.navigationReceivedElapsedNanos == 0 ? JSONObject.NULL : service.navigationReceivedElapsedNanos)
                        .put("last_feature_hex",service.lastFeatureHex == null ? JSONObject.NULL : service.lastFeatureHex)
                        .put("quality_received_elapsed_nanos",service.qualityReceivedElapsedNanos == 0 ? JSONObject.NULL : service.qualityReceivedElapsedNanos)
                        .put("last_notification_elapsed_nanos",service.lastNotification == 0 ? JSONObject.NULL : service.lastNotification)
                        .put("last_characteristic",service.lastCharacteristic == null ? JSONObject.NULL : service.lastCharacteristic)
                        .put("last_payload_hex",service.lastPayload == null ? JSONObject.NULL : service.lastPayload);
            } catch (Exception ignored) { return emptySnapshot(context); }
        }
    }
    private static JSONObject emptyPositionSnapshot(Context context) {
        boolean excluded=manualExcluded(context);
        try { return positionEnvelope("stopped",false,null,SystemClock.elapsedRealtimeNanos(),
                excluded ? "manual_excluded" : "disconnected",excluded,false,JSONObject.NULL); }
        catch (JSONException impossible) { return new JSONObject(); }
    }
    private static JSONObject emptySnapshot(Context context) {
        try { return emptyPositionSnapshot(context)
                .put("txt_packets",0).put("last_txt",JSONObject.NULL).put("txt_received_elapsed_nanos",JSONObject.NULL)
                .put("services",new JSONArray()).put("inventory_truncated",false).put("subscription_failed",false).put("packets",0)
                .put("last_navigation_hex",JSONObject.NULL).put("navigation_packets",0).put("navigation_received_elapsed_nanos",JSONObject.NULL).put("last_feature_hex",JSONObject.NULL)
                .put("last_location_hex",JSONObject.NULL).put("location_sequence",0).put("location_received_elapsed_nanos",JSONObject.NULL)
                .put("location_received_timestamp_ms",JSONObject.NULL).put("last_quality_hex",JSONObject.NULL).put("quality_received_elapsed_nanos",JSONObject.NULL)
                .put("last_notification_elapsed_nanos",JSONObject.NULL)
                .put("last_characteristic",JSONObject.NULL).put("last_payload_hex",JSONObject.NULL); }
        catch (Exception impossible) { return new JSONObject(); }
    }
    @Override public void onDestroy() { closeConnection(); resetData(); setState("stopped",false); if (instance==this) instance=null; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
