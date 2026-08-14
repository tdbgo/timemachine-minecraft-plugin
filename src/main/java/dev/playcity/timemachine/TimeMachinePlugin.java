package dev.playcity.timemachine;

import dev.playcity.timemachine.backup.BackupManager;
import dev.playcity.timemachine.command.TimeMachineCommand;
import dev.playcity.timemachine.config.ConfigAutoMerger;
import dev.playcity.timemachine.config.ConfigMergeResult;
import dev.playcity.timemachine.config.StorageSafetyValidator;
import dev.playcity.timemachine.config.TimeMachineSettings;
import dev.playcity.timemachine.i18n.LanguageMode;
import dev.playcity.timemachine.i18n.MessageCatalog;
import dev.playcity.timemachine.schedule.BackupScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class TimeMachinePlugin extends JavaPlugin {
    private TimeMachineSettings settings;
    private BackupManager backupManager;
    private BackupScheduler backupScheduler;
    private MessageCatalog messages = new MessageCatalog(LanguageMode.AUTO);

    @Override
    public void onEnable() {
        Path configPath = getDataFolder().toPath().resolve("config.yml");
        boolean firstRun = Files.notExists(configPath);
        saveDefaultConfig();
        if (!prepareConfig(configPath, firstRun)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        TimeMachineCommand commandHandler = new TimeMachineCommand(this);
        PluginCommand command = Objects.requireNonNull(getCommand("timemachine"), "timemachine command missing");
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);

        TimeMachineSettings initialSettings;
        try {
            initialSettings = TimeMachineSettings.load(this);
        } catch (Exception ex) {
            getLogger().severe("Failed to load TimeMachine settings: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Initializing TimeMachine storage in the background.");
        initializeRuntimeAsync(initialSettings, success -> {
            if (success) {
                if (firstRun) {
                    getLogger().info("TimeMachine enabled with safe defaults. Run /tmb backup to create the first FULL snapshot.");
                } else {
                    getLogger().info("TimeMachine enabled.");
                }
                return;
            }
            getServer().getPluginManager().disablePlugin(this);
        });
    }

    @Override
    public void onDisable() {
        PluginShutdownSequence.run(
                () -> {
                    if (backupScheduler != null) {
                        backupScheduler.stop();
                    }
                },
                () -> {
                    if (backupManager != null) {
                        backupManager.shutdown();
                    }
                });
    }

    public void reloadPlugin(Consumer<Boolean> completion) {
        Objects.requireNonNull(completion, "completion");
        if (!prepareConfig(getDataFolder().toPath().resolve("config.yml"), false)) {
            completion.accept(false);
            return;
        }
        TimeMachineSettings candidateSettings;
        try {
            candidateSettings = TimeMachineSettings.load(this);
        } catch (Exception ex) {
            getLogger().severe("Failed to load candidate TimeMachine settings: " + ex.getMessage());
            completion.accept(false);
            return;
        }
        if (!validateSettings(candidateSettings)) {
            completion.accept(false);
            return;
        }

        if (backupManager == null) {
            backupManager = new BackupManager(this);
        }
        if (!backupManager.reloadAsync(candidateSettings, success -> {
            if (success) {
                activateSettings(candidateSettings);
            }
            completion.accept(success);
        })) {
            completion.accept(false);
        }
    }

    public BackupManager getBackupManager() {
        return backupManager;
    }

    public boolean isRuntimeReady() {
        return backupManager != null && backupManager.isInitialized();
    }

    public ZoneId getDisplayTimezone() {
        return settings == null ? ZoneId.systemDefault() : settings.timezone();
    }

    public Optional<TimeMachineSettings> getActiveSettings() {
        return Optional.ofNullable(settings);
    }

    public MessageCatalog getMessages() {
        return messages;
    }

    public Optional<BackupScheduler.ScheduledRun> getNextScheduledRun() {
        BackupScheduler scheduler = backupScheduler;
        return scheduler == null ? Optional.empty() : scheduler.nextRun();
    }

    private void initializeRuntimeAsync(
            TimeMachineSettings candidateSettings,
            Consumer<Boolean> completion) {
        if (!validateSettings(candidateSettings)) {
            completion.accept(false);
            return;
        }

        if (backupManager != null) {
            getLogger().severe("TimeMachine runtime initialization was requested more than once.");
            completion.accept(false);
            return;
        }
        backupManager = new BackupManager(this);
        if (!backupManager.reloadAsync(candidateSettings, success -> {
            if (success) {
                activateSettings(candidateSettings);
            }
            completion.accept(success);
        })) {
            completion.accept(false);
        }
    }

    private boolean validateSettings(TimeMachineSettings candidateSettings) {
        List<String> issues = StorageSafetyValidator.validate(candidateSettings);
        if (!issues.isEmpty()) {
            for (String issue : issues) {
                getLogger().severe(issue);
            }
            return false;
        }
        return true;
    }

    private void activateSettings(TimeMachineSettings candidateSettings) {
        if (backupScheduler != null) {
            backupScheduler.stop();
        }
        this.settings = candidateSettings;
        this.messages = new MessageCatalog(candidateSettings.language());
        this.backupScheduler = new BackupScheduler(this, backupManager, settings);
        this.backupScheduler.start();
    }

    private boolean prepareConfig(Path configPath, boolean firstRun) {
        if (firstRun) {
            try {
                reloadConfig();
                return true;
            } catch (Exception ex) {
                getLogger().severe("Failed to load newly created config.yml: " + ex.getMessage());
                return false;
            }
        }

        try {
            ConfigMergeResult result = ConfigAutoMerger.merge(this, configPath);
            if (result.changed()) {
                getLogger().info("TimeMachine config auto-merged to version "
                        + result.toVersion()
                        + " from version "
                        + result.fromVersion()
                        + ".");
                if (result.backupPath() != null) {
                    getLogger().info("Previous config backup created at: " + result.backupPath());
                }
            }
            for (String warning : result.warnings()) {
                getLogger().warning(warning);
            }
        } catch (Exception ex) {
            getLogger().severe("Failed to validate/merge config.yml; current runtime was kept: " + ex.getMessage());
            return false;
        }
        try {
            reloadConfig();
            return true;
        } catch (Exception ex) {
            getLogger().severe("Failed to reload config.yml; current runtime was kept: " + ex.getMessage());
            return false;
        }
    }
}
