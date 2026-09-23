package dev.playcity.timemachine.schedule;

import dev.playcity.timemachine.TimeMachinePlugin;
import dev.playcity.timemachine.backup.BackupManager;
import dev.playcity.timemachine.backup.BackupRequest;
import dev.playcity.timemachine.config.TimeMachineSettings;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.scheduler.BukkitTask;

public final class BackupScheduler {
    private static final DateTimeFormatter SLOT_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm");

    private final TimeMachinePlugin plugin;
    private final BackupScheduleOperations operations;
    private final TimeMachineSettings settings;
    private final Set<String> executedSlotKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, DueSlot> pendingSlots = new ConcurrentHashMap<>();

    private volatile BukkitTask task;
    private volatile boolean stopped = true;
    private volatile ZonedDateTime lastIntervalRun;
    private volatile ZonedDateTime lastScheduleObservation;
    private boolean startupRegularCatchupQueued;
    private boolean startupFullCatchupQueued;

    public BackupScheduler(TimeMachinePlugin plugin, BackupManager backupManager, TimeMachineSettings settings) {
        this(plugin, new BackupScheduleOperations() {
            @Override
            public Optional<Instant> latestSnapshotTime() {
                return backupManager.getLatestSnapshotTime();
            }

            @Override
            public Optional<Instant> latestFullSnapshotTime() {
                return backupManager.getLatestFullSnapshotTime();
            }

            @Override
            public boolean startScheduledBackup(BackupRequest request) {
                return backupManager.startScheduledBackup(request);
            }
        }, settings);
    }

    BackupScheduler(
            TimeMachinePlugin plugin,
            BackupScheduleOperations operations,
            TimeMachineSettings settings) {
        this.plugin = plugin;
        this.operations = operations;
        this.settings = settings;
    }

    public void start() {
        stopped = false;
        if (!settings.scheduleEnabled() && !settings.fullBackupSchedule().enabled()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(settings.timezone()).withSecond(0).withNano(0);
        initializeAt(now);
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::tick, 20L, 1200L);
    }

    void initializeAt(ZonedDateTime now) {
        stopped = false;
        if (settings.scheduleEnabled() && settings.intervalMinutes() > 0) {
            lastIntervalRun = now;
        }
        lastScheduleObservation = now;
    }

    public void stop() {
        stopped = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public Optional<ScheduledRun> nextRun() {
        ZonedDateTime now = ZonedDateTime.now(settings.timezone()).withSecond(0).withNano(0);
        return nextRunAt(now);
    }

    Optional<ScheduledRun> nextRunAt(ZonedDateTime now) {
        ScheduledRun next = pendingSlots.values().stream()
                .map(slot -> new ScheduledRun(
                        "queued " + slot.request().trigger(),
                        slot.slotTime(),
                        slot.request().fullBackup()))
                .min(Comparator.comparing(ScheduledRun::at))
                .orElse(null);

        if (settings.scheduleEnabled()) {
            if (settings.intervalMinutes() > 0) {
                ZonedDateTime base = lastIntervalRun == null ? now : lastIntervalRun;
                next = earlier(next, new ScheduledRun(
                        "interval",
                        base.plusMinutes(settings.intervalMinutes()),
                        false));
            }
            next = earlier(next, nextDailyRun(now));
            next = earlier(next, nextMonthlyRun(
                    now,
                    settings.monthlyDays(),
                    settings.monthlyTimes(),
                    "monthly",
                    false));
        }

        TimeMachineSettings.FullBackupScheduleSettings full = settings.fullBackupSchedule();
        if (full.enabled()) {
            next = earlier(next, nextMonthlyRun(
                    now,
                    full.monthlyDays(),
                    full.monthlyTimes(),
                    "full-monthly",
                    true));
        }
        return Optional.ofNullable(next);
    }

    private ScheduledRun nextDailyRun(ZonedDateTime now) {
        ScheduledRun next = null;
        for (LocalTime time : settings.dailyTimes()) {
            ZonedDateTime candidate = now.toLocalDate().atTime(time).atZone(settings.timezone());
            if (!candidate.isAfter(now)) {
                candidate = candidate.plusDays(1);
            }
            next = earlier(next, new ScheduledRun("daily", candidate, false));
        }
        return next;
    }

    private ScheduledRun nextMonthlyRun(
            ZonedDateTime now,
            Iterable<Integer> days,
            Iterable<LocalTime> times,
            String trigger,
            boolean fullBackup) {
        ScheduledRun next = null;
        YearMonth currentMonth = YearMonth.from(now);
        for (int monthOffset = 0; monthOffset <= 12; monthOffset++) {
            YearMonth month = currentMonth.plusMonths(monthOffset);
            for (int day : days) {
                if (day > month.lengthOfMonth()) {
                    continue;
                }
                for (LocalTime time : times) {
                    ZonedDateTime candidate = month.atDay(day).atTime(time).atZone(settings.timezone());
                    if (!candidate.isAfter(now)) {
                        continue;
                    }
                    next = earlier(next, new ScheduledRun(trigger, candidate, fullBackup));
                }
            }
            if (next != null) {
                return next;
            }
        }
        return next;
    }

    private ScheduledRun earlier(ScheduledRun current, ScheduledRun candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.at().isBefore(current.at())) {
            return candidate;
        }
        return current;
    }

    private void tick() {
        if (stopped) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(settings.timezone()).withSecond(0).withNano(0);
        tickAt(now);
    }

    void tickAt(ZonedDateTime now) {
        if (stopped) {
            return;
        }

        if (!startupRegularCatchupQueued) {
            queueStartupCatchup(now, false);
            startupRegularCatchupQueued = true;
        }
        if (!startupFullCatchupQueued) {
            queueStartupCatchup(now, true);
            startupFullCatchupQueued = true;
        }

        if (lastScheduleObservation != null) {
            queueScheduledSlots(lastScheduleObservation, now);
        }
        drainPendingSlots();

        if (stopped) {
            return;
        }

        if (settings.scheduleEnabled() && settings.intervalMinutes() > 0 && shouldRunInterval(now)) {
            if (operations.startScheduledBackup(new BackupRequest("interval", false, null, "", "scheduler"))) {
                lastIntervalRun = now;
            }
        }

        cleanupExecutedKeys(now);
        lastScheduleObservation = now;
    }

    private void queueStartupCatchup(ZonedDateTime now, boolean fullBackup) {
        if (fullBackup) {
            TimeMachineSettings.FullBackupScheduleSettings fullSettings = settings.fullBackupSchedule();
            if (!fullSettings.enabled() || !fullSettings.catchUpOnStartup()) {
                return;
            }
            Optional<Instant> baseline = operations.latestFullSnapshotTime();
            if (baseline.isEmpty()) {
                return;
            }
            DueSlot slot = latestFullDueSlotBetween(baseline.get().atZone(settings.timezone()), now);
            if (slot != null) {
                queueSlot(slot);
            }
            return;
        }

        if (!settings.scheduleEnabled() || !settings.catchUpOnStartup()) {
            return;
        }
        Optional<Instant> baseline = operations.latestSnapshotTime();
        if (baseline.isEmpty()) {
            return;
        }
        DueSlot slot = latestRegularDueSlotBetween(
                baseline.get().atZone(settings.timezone()),
                now,
                true);
        if (slot != null) {
            queueSlot(slot);
        }
    }

    private void queueScheduledSlots(ZonedDateTime fromExclusive, ZonedDateTime nowInclusive) {
        if (settings.scheduleEnabled()) {
            DueSlot latestRegular = latestRegularDueSlotBetween(fromExclusive, nowInclusive, false);
            if (latestRegular != null) {
                queueSlot(latestRegular);
            }
        }

        TimeMachineSettings.FullBackupScheduleSettings fullSettings = settings.fullBackupSchedule();
        if (fullSettings.enabled()) {
            DueSlot latestFull = latestMonthlyDueSlotBetween(
                    fromExclusive,
                    nowInclusive,
                    fullSettings.monthlyDays(),
                    fullSettings.monthlyTimes(),
                    "full-monthly",
                    true,
                    "scheduled full backup");
            if (latestFull != null) {
                queueSlot(latestFull);
            }
        }
    }

    private DueSlot latestRegularDueSlotBetween(
            ZonedDateTime baselineExclusive,
            ZonedDateTime nowInclusive,
            boolean catchup) {
        DueSlot latest = null;

        for (LocalTime time : settings.dailyTimes()) {
            ZonedDateTime slot = nowInclusive.with(time);
            if (slot.isAfter(nowInclusive)) {
                slot = slot.minusDays(1);
            }
            if (slot.isAfter(baselineExclusive)) {
                String trigger = catchup ? "catchup" : "daily";
                latest = later(latest, new DueSlot(
                        slotKey(slot, false),
                        slot,
                        new BackupRequest(
                                trigger,
                                false,
                                null,
                                catchup ? "missed daily schedule" : "",
                                "scheduler")));
            }
        }

        latest = later(latest, latestMonthlyDueSlotBetween(
                baselineExclusive,
                nowInclusive,
                settings.monthlyDays(),
                settings.monthlyTimes(),
                catchup ? "catchup" : "monthly",
                false,
                catchup ? "missed monthly schedule" : ""));
        return latest;
    }

    private DueSlot latestFullDueSlotBetween(ZonedDateTime baselineExclusive, ZonedDateTime nowInclusive) {
        TimeMachineSettings.FullBackupScheduleSettings fullSettings = settings.fullBackupSchedule();
        return latestMonthlyDueSlotBetween(
                baselineExclusive,
                nowInclusive,
                fullSettings.monthlyDays(),
                fullSettings.monthlyTimes(),
                "full-catchup",
                true,
                "missed full monthly schedule");
    }

    private DueSlot latestMonthlyDueSlotBetween(
            ZonedDateTime baselineExclusive,
            ZonedDateTime nowInclusive,
            Iterable<Integer> days,
            Iterable<LocalTime> times,
            String trigger,
            boolean fullBackup,
            String message) {
        DueSlot latest = null;
        ZonedDateTime monthStart = nowInclusive.withDayOfMonth(1);
        for (int monthOffset = 0; monthOffset <= 12; monthOffset++) {
            ZonedDateTime month = monthStart.minusMonths(monthOffset);
            YearMonth yearMonth = YearMonth.from(month);
            for (int day : days) {
                if (day > yearMonth.lengthOfMonth()) {
                    continue;
                }
                for (LocalTime time : times) {
                    ZonedDateTime slot = month.withDayOfMonth(day).with(time);
                    if (slot.isAfter(nowInclusive) || !slot.isAfter(baselineExclusive)) {
                        continue;
                    }
                    latest = later(latest, new DueSlot(
                            slotKey(slot, fullBackup),
                            slot,
                            new BackupRequest(trigger, fullBackup, null, message, "scheduler")));
                }
            }
        }
        return latest;
    }

    private DueSlot later(DueSlot current, DueSlot candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.slotTime().isAfter(current.slotTime())) {
            return candidate;
        }
        return current;
    }

    private String slotKey(ZonedDateTime slotTime, boolean fullBackup) {
        return (fullBackup ? "full:" : "regular:") + slotTime.format(SLOT_KEY_FORMATTER);
    }

    private void queueSlot(DueSlot slot) {
        if (executedSlotKeys.contains(slot.key())) {
            return;
        }
        pendingSlots.putIfAbsent(slot.key(), slot);
    }

    private void drainPendingSlots() {
        for (DueSlot slot : pendingSlots.values().stream()
                .sorted(Comparator.comparing(DueSlot::slotTime))
                .toList()) {
            if (stopped) {
                return;
            }
            if (executedSlotKeys.contains(slot.key())) {
                pendingSlots.remove(slot.key());
                continue;
            }
            if (!operations.startScheduledBackup(slot.request())) {
                return;
            }
            executedSlotKeys.add(slot.key());
            pendingSlots.remove(slot.key());
        }
    }

    private void cleanupExecutedKeys(ZonedDateTime now) {
        String cutoff = now.minusDays(40).format(SLOT_KEY_FORMATTER);
        executedSlotKeys.removeIf(key -> key.substring(key.indexOf(':') + 1).compareTo(cutoff) < 0);
        pendingSlots.entrySet().removeIf(entry -> entry.getKey().substring(entry.getKey().indexOf(':') + 1).compareTo(cutoff) < 0);
    }

    private boolean shouldRunInterval(ZonedDateTime now) {
        if (lastIntervalRun == null) {
            return true;
        }
        return Duration.between(lastIntervalRun, now).toMinutes() >= settings.intervalMinutes();
    }

    private record DueSlot(String key, ZonedDateTime slotTime, BackupRequest request) {
    }

    public record ScheduledRun(String trigger, ZonedDateTime at, boolean fullBackup) {
    }
}
