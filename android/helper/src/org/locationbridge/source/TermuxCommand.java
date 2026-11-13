package org.locationbridge.source;

/** The entire externally runnable command vocabulary. */
enum TermuxCommand {
    USB, WIFI, STOP, STATUS;
    static final String SCRIPT = "/data/data/com.termux/files/home/location-bridge/bridge-launch.sh";
    String operation() { return name().toLowerCase(java.util.Locale.ROOT); }
    String[] arguments() {
        switch (this) {
            case USB: return new String[]{"start", "--mode", "fusion", "--transport", "usb"};
            case WIFI: return new String[]{"start", "--mode", "fusion", "--transport", "wifi"};
            case STOP: return new String[]{"stop"};
            case STATUS: return new String[]{"status"};
            default: throw new AssertionError();
        }
    }
    boolean isStart() { return this == USB || this == WIFI; }
}
