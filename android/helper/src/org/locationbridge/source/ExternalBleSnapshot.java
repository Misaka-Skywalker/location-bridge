package org.locationbridge.source;

import android.os.SystemClock;
import android.content.Context;
import org.json.JSONObject;

/** Converts selected-device observations without associating independent quality packets. */
final class ExternalBleSnapshot {
    private static Object value(Object item) { return item == null ? JSONObject.NULL : item; }
    static JSONObject sample(ExternalPositionStore.Candidate candidate) throws org.json.JSONException {
        LnsDecoder.LocationSpeed point=candidate.position;
        return new JSONObject().put("source_type","ble_lns").put("sequence",candidate.sequence)
            .put("latitude",point.latitude).put("longitude",point.longitude).put("coordinate_system","WGS84")
            .put("position_status",point.positionStatus).put("timestamp_ms",value(point.utcMillis))
            .put("received_timestamp_ms",candidate.receivedUtcMillis).put("received_elapsed_realtime_nanos",candidate.receivedNanos)
            .put("accuracy_m",JSONObject.NULL).put("is_mock",JSONObject.NULL)
            .put("speed_m_s",value(point.speedMps)).put("altitude_m",value(point.elevationM))
            .put("altitude_datum",point.elevationM == null ? JSONObject.NULL : "unknown")
            .put("elevation_source",point.elevationSource)
            .put("course_deg",point.headingSource == 0 ? value(point.headingDeg) : JSONObject.NULL)
            .put("heading_deg",value(point.headingDeg)).put("heading_source",point.headingSource);
    }
    static JSONObject snapshot(Context context) {
        JSONObject out = BleReceiverService.snapshot(context);
        try {
            out.put("elapsed_realtime_nanos",SystemClock.elapsedRealtimeNanos());
            out.put("quality",JSONObject.NULL);
            if (!out.optBoolean("connected")) return out;
            String location = out.optString("last_location_hex", "");
            if (!location.isEmpty() && !location.equals("null")) {
                try {
                    LnsDecoder.LocationSpeed point = LnsDecoder.decodeLocationSpeed(decode(location));
                    out.put("location_status",point.positionStatus).put("location_flags",point.flags);
                } catch (IllegalArgumentException e) { out.put("location_error","invalid_lns_payload"); }
            }
            String quality = out.optString("last_quality_hex", "");
            if (!quality.isEmpty() && !quality.equals("null")) {
                try {
                    LnsDecoder.Quality q = LnsDecoder.decodeQuality(decode(quality));
                    out.put("quality",new JSONObject().put("position_status",q.positionStatus)
                        .put("horizontal_error_m",value(q.horizontalErrorMeters))
                        .put("vertical_error_m",value(q.verticalErrorMeters))
                        .put("hdop",value(q.hdop)).put("vdop",value(q.vdop))
                        .put("association","unverified")
                        .put("received_elapsed_realtime_nanos",out.getLong("quality_received_elapsed_nanos")));
                } catch (IllegalArgumentException e) { out.put("quality_error","invalid_lns_payload"); }
            }
        } catch (Exception e) {
            try { out.put("sample",JSONObject.NULL).put("decode_error","invalid_observation"); } catch(Exception ignored) { }
        }
        return out;
    }
    private static byte[] decode(String text) {
        if (text.length() > 256 || (text.length() & 1) != 0 || !text.matches("[0-9a-fA-F]+"))
            throw new IllegalArgumentException("invalid hex");
        return AuthProtocol.decode(text);
    }
}
