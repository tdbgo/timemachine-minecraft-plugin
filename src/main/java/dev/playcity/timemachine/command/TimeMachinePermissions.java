package dev.playcity.timemachine.command;

final class TimeMachinePermissions {
    static final String VIEWER = "timemachine.viewer";
    static final String OPERATOR = "timemachine.operator";
    static final String ADMIN = "timemachine.admin";

    static final String BACKUP = "timemachine.backup";
    static final String STATUS = "timemachine.status";
    static final String DOCTOR = "timemachine.doctor";
    static final String HISTORY = "timemachine.history";
    static final String VERIFY = "timemachine.verify";
    static final String PRUNE = "timemachine.prune";
    static final String RECONCILE = "timemachine.reconcile";
    static final String RELOAD = "timemachine.reload";

    private TimeMachinePermissions() {
    }
}
