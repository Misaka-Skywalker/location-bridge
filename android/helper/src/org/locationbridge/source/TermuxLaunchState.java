package org.locationbridge.source;

/** Dispatch state describes a command request, not device connectivity. */
final class TermuxLaunchState {
    static final long PENDING_MS=12_000;
    private static final long COMMAND_DISPLAY_MS = 60_000;
    final String state, operation, nonce, error;
    final long issued;
    TermuxLaunchState(String state,long issued,String operation,String nonce,String error) {
        this.state=state; this.issued=issued; this.operation=operation; this.nonce=nonce; this.error=error;
    }
    String view(long now) {
        if ("pending".equals(state) && (now < issued || now-issued > PENDING_MS)) return "unconfirmed";
        return state;
    }
    static boolean showCommand(long issued, long reportSent, long now) {
        // A report at or after dispatch supersedes the command even after the report expires.
        return issued > 0 && now >= issued && now - issued <= COMMAND_DISPLAY_MS && issued > reportSent;
    }
    boolean busy(TermuxCommand command,long now) {
        return command.isStart() && ("usb".equals(operation) || "wifi".equals(operation)) && "pending".equals(view(now));
    }
    boolean accepts(String candidate,long now) {
        return nonce != null && nonce.equals(candidate) && now>=issued && ("pending".equals(state) || "unconfirmed".equals(state));
    }
    static String result(Integer err,Integer exitCode) {
        return err != null && err == -1 && exitCode != null && exitCode == 0 ? "completed" : "failed";
    }
}
