package org.locationbridge.source;

/** Two request slots retain start deduplication while displaying the latest command. */
final class TermuxLaunchSlots {
    TermuxLaunchState start = empty(), auxiliary = empty();
    String latest = "none";
    static TermuxLaunchState empty() { return new TermuxLaunchState("none",0,null,null,null); }
    boolean busy(TermuxCommand command,long now) { return start.busy(command,now); }
    TermuxLaunchState slot(TermuxCommand command) { return command.isStart() ? start : auxiliary; }
    void issue(TermuxCommand command,long now,String nonce) {
        TermuxLaunchState next=new TermuxLaunchState("pending",now,command.operation(),nonce,null);
        if (command.isStart()) { start=next; latest="start"; } else { auxiliary=next; latest="auxiliary"; }
    }
    TermuxLaunchState display() { return "start".equals(latest) ? start : "auxiliary".equals(latest) ? auxiliary : empty(); }
    boolean receive(String nonce,long now,Integer err,Integer exit) {
        if (start.accepts(nonce,now)) { start=finish(start,err,exit); return true; }
        if (auxiliary.accepts(nonce,now)) { auxiliary=finish(auxiliary,err,exit); return true; }
        return false;
    }
    private TermuxLaunchState finish(TermuxLaunchState s,Integer err,Integer exit) {
        String state=TermuxLaunchState.result(err,exit);
        return new TermuxLaunchState(state,s.issued,s.operation,null,"failed".equals(state)?"command_failed":null);
    }
}
