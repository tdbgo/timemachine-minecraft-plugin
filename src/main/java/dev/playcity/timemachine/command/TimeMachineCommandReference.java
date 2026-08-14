package dev.playcity.timemachine.command;

final class TimeMachineCommandReference {
    static final String PRIMARY_COMMAND = "/tmb";

    private TimeMachineCommandReference() {
    }

    static String command(String arguments) {
        return arguments == null || arguments.isBlank()
                ? PRIMARY_COMMAND
                : PRIMARY_COMMAND + " " + arguments;
    }
}
