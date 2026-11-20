package org.locationbridge.source;

/** The entire externally runnable command vocabulary. */
enum TermuxCommand {
    AUTO, USB, WIFI, STOP, STATUS;
    static final String SCRIPT = "/data/data/com.termux/files/home/location-bridge/bridge-launch.sh";
    String operation() { return name().toLowerCase(java.util.Locale.ROOT); }
    String[] arguments() {
        switch (this) {
            case AUTO: case USB: case WIFI:
                return new String[]{"start", "--mode", "fusion", "--transport", operation()};
            case STOP: return new String[]{"stop"};
            case STATUS: return new String[]{"status"};
            default: throw new AssertionError();
        }
    }
    boolean isStart() { return this == AUTO || this == USB || this == WIFI; }
}
