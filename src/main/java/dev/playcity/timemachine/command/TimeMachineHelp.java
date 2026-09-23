package dev.playcity.timemachine.command;

import dev.playcity.timemachine.i18n.MessageCatalog;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;

final class TimeMachineHelp {
    private static final List<String> TOPICS = List.of("advanced", "config", "permissions");

    private TimeMachineHelp() {
    }

    static void send(MessageCatalog messages, CommandSender sender, String topic) {
        switch (topic == null ? "" : topic.toLowerCase(Locale.ROOT)) {
            case "", "quick", "basic" -> sendQuickStart(messages, sender);
            case "advanced" -> sendAdvanced(messages, sender);
            case "config" -> sendConfig(messages, sender);
            case "permissions" -> sendPermissions(messages, sender);
            default -> messages.send(
                    sender,
                    "help.unknown",
                    TimeMachineCommandReference.command("help [advanced|config|permissions]"));
        }
    }

    static List<String> topics() {
        return TOPICS;
    }

    private static void sendQuickStart(MessageCatalog messages, CommandSender sender) {
        messages.send(sender, "help.quick.title");
        sendIfAllowed(messages, sender, TimeMachinePermissions.BACKUP, "help.quick.backup",
                TimeMachineCommandReference.command("backup"));
        sendIfAllowed(messages, sender, TimeMachinePermissions.STATUS, "help.quick.status",
                TimeMachineCommandReference.command("status"));
        sendIfAllowed(messages, sender, TimeMachinePermissions.DOCTOR, "help.quick.doctor",
                TimeMachineCommandReference.command("doctor"));
        messages.send(sender, "help.more",
                TimeMachineCommandReference.command("help advanced | config | permissions"));
    }

    private static void sendAdvanced(MessageCatalog messages, CommandSender sender) {
        messages.send(sender, "help.advanced.title");
        int visible = 0;
        visible += sendCommandIfAllowed(sender, TimeMachinePermissions.BACKUP,
                TimeMachineCommandReference.command("backup [world] [--full] [--message <text>]"));
        visible += sendCommandIfAllowed(sender, TimeMachinePermissions.HISTORY,
                TimeMachineCommandReference.command("history [count]"));
        visible += sendCommandIfAllowed(sender, TimeMachinePermissions.VERIFY,
                TimeMachineCommandReference.command("verify <snapshotId>"));
        visible += sendCommandIfAllowed(sender, TimeMachinePermissions.PRUNE,
                TimeMachineCommandReference.command("prune [confirm <token>]"));
        visible += sendCommandIfAllowed(sender, TimeMachinePermissions.PRUNE,
                TimeMachineCommandReference.command("cleanup [confirm <token>]"));
        visible += sendCommandIfAllowed(sender, TimeMachinePermissions.RECONCILE,
                TimeMachineCommandReference.command("reconcile"));
        visible += sendCommandIfAllowed(sender, TimeMachinePermissions.RELOAD,
                TimeMachineCommandReference.command("reload"));
        if (visible == 0) {
            messages.send(sender, "help.advanced.none");
        }
    }

    private static void sendConfig(MessageCatalog messages, CommandSender sender) {
        messages.send(sender, "help.config.title");
        messages.send(sender, "help.config.language");
        messages.send(sender, "help.config.change_detection");
        messages.send(sender, "help.config.schedule");
        messages.send(sender, "help.config.timezone");
        messages.send(sender, "help.config.after",
                TimeMachineCommandReference.command("reload"),
                TimeMachineCommandReference.command("doctor"));
    }

    private static void sendPermissions(MessageCatalog messages, CommandSender sender) {
        messages.send(sender, "help.permissions.title");
        messages.send(sender, "help.permissions.viewer", TimeMachinePermissions.VIEWER);
        messages.send(sender, "help.permissions.operator", TimeMachinePermissions.OPERATOR);
        messages.send(sender, "help.permissions.admin", TimeMachinePermissions.ADMIN);
        messages.send(sender, "help.permissions.note");
    }

    private static int sendIfAllowed(
            MessageCatalog messages,
            CommandSender sender,
            String permission,
            String key,
            Object... arguments) {
        if (!sender.hasPermission(permission)) {
            return 0;
        }
        messages.send(sender, key, arguments);
        return 1;
    }

    private static int sendCommandIfAllowed(CommandSender sender, String permission, String command) {
        if (!sender.hasPermission(permission)) {
            return 0;
        }
        sender.sendMessage(command);
        return 1;
    }
}
