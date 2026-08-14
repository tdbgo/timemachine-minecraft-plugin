package dev.playcity.timemachine;

final class PluginShutdownSequence {
    private PluginShutdownSequence() {
    }

    static void run(Runnable stopScheduler, Runnable shutdownManager) {
        try {
            stopScheduler.run();
        } finally {
            shutdownManager.run();
        }
    }
}
