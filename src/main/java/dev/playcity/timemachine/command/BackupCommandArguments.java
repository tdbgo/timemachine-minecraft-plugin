package dev.playcity.timemachine.command;

import java.util.Arrays;

record BackupCommandArguments(
        boolean fullBackup,
        String worldFilter,
        String message) {

    static BackupCommandArguments parse(String[] args) {
        boolean fullBackup = false;
        String worldFilter = null;
        String message = "";

        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            if ("--full".equalsIgnoreCase(argument)) {
                fullBackup = true;
                continue;
            }
            if ("--message".equalsIgnoreCase(argument)) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing text after --message.");
                }
                message = String.join(" ", Arrays.copyOfRange(args, index + 1, args.length));
                break;
            }
            if (argument.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option: " + argument);
            }
            if (worldFilter != null) {
                throw new IllegalArgumentException("Only one world can be selected.");
            }
            worldFilter = argument;
        }
        return new BackupCommandArguments(fullBackup, worldFilter, message);
    }
}
