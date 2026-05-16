package me.prexorjustin.prexorcloud.server.shared.bukkit;

import java.util.ArrayDeque;
import java.util.Deque;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Measures server TPS by counting Bukkit scheduler ticks.
 *
 * <p>
 * Schedules a task that runs every tick. Every second it records how many ticks
 * actually fired, then computes 1m / 5m / 15m rolling averages from a ring
 * buffer of per-second samples. Values are capped at 20.0.
 * </p>
 */
public final class TickCounter {

    private static final int SAMPLES_1M = 60;
    private static final int SAMPLES_5M = 300;
    private static final int SAMPLES_15M = 900;
    private static final double MAX_TPS = 20.0;

    private final Deque<Integer> samples = new ArrayDeque<>(SAMPLES_15M + 1);
    private int ticksThisSecond = 0;
    private long lastSecondMs = System.currentTimeMillis();

    public void start(JavaPlugin plugin) {
        new BukkitRunnable() {

            @Override
            public void run() {
                ticksThisSecond++;
                long now = System.currentTimeMillis();
                if (now - lastSecondMs >= 1000L) {
                    recordSample();
                    lastSecondMs = now;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private synchronized void recordSample() {
        samples.addLast(ticksThisSecond);
        ticksThisSecond = 0;
        while (samples.size() > SAMPLES_15M) {
            samples.removeFirst();
        }
    }

    public synchronized double tps1m() {
        return average(SAMPLES_1M);
    }

    public synchronized double tps5m() {
        return average(SAMPLES_5M);
    }

    public synchronized double tps15m() {
        return average(SAMPLES_15M);
    }

    private double average(int maxSamples) {
        if (samples.isEmpty()) return MAX_TPS;
        int count = 0;
        long sum = 0;
        var it = samples.descendingIterator();
        while (it.hasNext() && count < maxSamples) {
            sum += it.next();
            count++;
        }
        if (count == 0) return MAX_TPS;
        double avg = (double) sum / count;
        return Math.min(avg, MAX_TPS);
    }
}
