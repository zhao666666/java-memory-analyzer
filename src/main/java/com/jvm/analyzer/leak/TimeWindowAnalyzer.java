package com.jvm.analyzer.leak;

import com.jvm.analyzer.core.*;
import com.jvm.analyzer.heap.ObjectTracker;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Time Window Analyzer - Analyzes memory changes over time windows
 *
 * Provides sliding window analysis for detecting memory growth patterns.
 * Used for leak detection and trend analysis.
 *
 * Thread-safe implementation.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class TimeWindowAnalyzer {

    private final int windowSize;
    private final Deque<WindowData> windows = new ConcurrentLinkedDeque<>();
    private final Map<String, ClassWindowStats> classStats = new ConcurrentHashMap<>();

    private final AtomicLong snapshotCount = new AtomicLong(0);
    private final AtomicLong firstSnapshotTime = new AtomicLong(0);
    private final AtomicLong lastSnapshotTime = new AtomicLong(0);

    /**
     * Create time window analyzer
     *
     * @param windowSize Number of snapshots per window
     */
    public TimeWindowAnalyzer(int windowSize) {
        this.windowSize = windowSize;
    }

    /**
     * Add a memory snapshot for analysis
     *
     * @param snapshot Memory snapshot
     */
    public void addSnapshot(MemorySnapshot snapshot) {
        long now = System.currentTimeMillis();

        // Update timestamps
        if (firstSnapshotTime.get() == 0) {
            firstSnapshotTime.set(snapshot.getTimestamp());
        }
        lastSnapshotTime.set(snapshot.getTimestamp());

        // Create window data
        WindowData data = new WindowData(snapshot);
        windows.addLast(data);

        // Maintain window size
        while (windows.size() > windowSize) {
            windows.removeFirst();
        }

        // Update class statistics
        updateClassStats(snapshot);

        snapshotCount.incrementAndGet();
    }

    /**
     * Update class statistics
     */
    private void updateClassStats(MemorySnapshot snapshot) {
        for (Map.Entry<String, MemorySnapshot.ClassStats> entry : snapshot.getClassStats().entrySet()) {
            String className = entry.getKey();
            MemorySnapshot.ClassStats stats = entry.getValue();

            classStats.compute(className, (key, existing) -> {
                ClassWindowStats newStats = new ClassWindowStats(className);

                if (existing != null) {
                    newStats.instanceCounts = new long[Math.min(windowSize, existing.instanceCounts.length + 1)];
                    System.arraycopy(existing.instanceCounts, 0, newStats.instanceCounts,
                                    1, existing.instanceCounts.length);
                    newStats.instanceCounts[0] = stats.instanceCount;

                    newStats.sizeValues = new long[Math.min(windowSize, existing.sizeValues.length + 1)];
                    System.arraycopy(existing.sizeValues, 0, newStats.sizeValues,
                                    1, existing.sizeValues.length);
                    newStats.sizeValues[0] = stats.totalSize;

                    newStats.count = Math.min(windowSize, existing.count + 1);
                } else {
                    newStats.instanceCounts = new long[]{stats.instanceCount};
                    newStats.sizeValues = new long[]{stats.totalSize};
                    newStats.count = 1;
                }

                return newStats;
            });
        }
    }

    /**
     * Analyze current state
     *
     * @return Map of class name to window stats
     */
    public Map<String, WindowStats> analyze(Map<String, ObjectTracker.ClassInfo> currentStats) {
        Map<String, WindowStats> results = new ConcurrentHashMap<>();

        for (Map.Entry<String, ClassWindowStats> entry : classStats.entrySet()) {
            String className = entry.getKey();
            ClassWindowStats stats = entry.getValue();

            if (stats.count >= 3) { // Need at least 3 data points
                WindowStats ws = createWindowStats(stats, currentStats.get(className));
                if (ws != null) {
                    results.put(className, ws);
                }
            }
        }

        return results;
    }

    /**
     * Create window stats for a class
     */
    private WindowStats createWindowStats(ClassWindowStats stats, ObjectTracker.ClassInfo current) {
        if (stats.count < 3) {
            return null;
        }

        // Calculate growth pattern
        int growthCount = 0;
        long totalGrowth = 0;
        long maxInstanceCount = 0;
        long minInstanceCount = Long.MAX_VALUE;

        for (int i = 0; i < stats.count - 1; i++) {
            long delta = stats.instanceCounts[i] - stats.instanceCounts[i + 1];
            if (delta > 0) {
                growthCount++;
                totalGrowth += delta;
            }
        }

        for (int i = 0; i < stats.count; i++) {
            maxInstanceCount = Math.max(maxInstanceCount, stats.instanceCounts[i]);
            minInstanceCount = Math.min(minInstanceCount, stats.instanceCounts[i]);
        }

        // Calculate trend (linear regression slope)
        double slope = calculateSlope(stats.instanceCounts, stats.count);

        return new WindowStats(
            stats.className,
            growthCount,
            totalGrowth,
            maxInstanceCount,
            minInstanceCount,
            slope,
            current != null ? current.instanceCount : 0,
            current != null ? current.totalSize : 0
        );
    }

    /**
     * Calculate linear regression slope
     */
    private double calculateSlope(long[] values, int count) {
        if (count < 2) return 0;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        for (int i = 0; i < count; i++) {
            double x = i;
            double y = values[i];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denominator = count * sumX2 - sumX * sumX;
        if (Math.abs(denominator) < 0.0001) return 0;

        return (count * sumXY - sumX * sumY) / denominator;
    }

    /**
     * Get window size
     */
    public int getWindowSize() {
        return windowSize;
    }

    /**
     * Get snapshot count
     */
    public long getSnapshotCount() {
        return snapshotCount.get();
    }

    /**
     * Get analysis duration in milliseconds
     */
    public long getAnalysisDuration() {
        long first = firstSnapshotTime.get();
        long last = lastSnapshotTime.get();
        return first > 0 ? last - first : 0;
    }

    /**
     * Clear all data
     */
    public void clear() {
        windows.clear();
        classStats.clear();
        snapshotCount.set(0);
        firstSnapshotTime.set(0);
        lastSnapshotTime.set(0);
    }

    /**
     * Get recent windows
     */
    public List<WindowData> getWindows() {
        return new ArrayList<>(windows);
    }

    /**
     * Window data holder
     */
    public static class WindowData {
        public final long snapshotId;
        public final long timestamp;
        public final long heapUsed;
        public final Map<String, MemorySnapshot.ClassStats> classStats;

        public WindowData(MemorySnapshot snapshot) {
            this.snapshotId = snapshot.getSnapshotId();
            this.timestamp = snapshot.getTimestamp();
            this.heapUsed = snapshot.getTotalHeapUsed();
            this.classStats = new HashMap<>(snapshot.getClassStats());
        }
    }

    /**
     * Class window statistics
     */
    public static class ClassWindowStats {
        public final String className;
        public long[] instanceCounts;
        public long[] sizeValues;
        public int count;

        public ClassWindowStats(String className) {
            this.className = className;
        }
    }

    /**
     * Window analysis results
     */
    public static class WindowStats {
        public final String className;
        public final int growthCount;
        public final long totalGrowth;
        public final long maxInstanceCount;
        public final long minInstanceCount;
        public final double slope;
        public final int currentInstances;
        public final long currentSize;

        public WindowStats(String className, int growthCount, long totalGrowth,
                          long maxInstanceCount, long minInstanceCount,
                          double slope, int currentInstances, long currentSize) {
            this.className = className;
            this.growthCount = growthCount;
            this.totalGrowth = totalGrowth;
            this.maxInstanceCount = maxInstanceCount;
            this.minInstanceCount = minInstanceCount;
            this.slope = slope;
            this.currentInstances = currentInstances;
            this.currentSize = currentSize;
        }

        /**
         * Check if consistent growth detected
         */
        public boolean isConsistentGrowth() {
            return growthCount >= (maxInstanceCount > 0 ? maxInstanceCount / 4 : 1);
        }

        /**
         * Get growth rate (instances per window)
         */
        public double getGrowthRate() {
            return count() > 0 ? (double) totalGrowth / count() : 0;
        }

        private int count() {
            return growthCount > 0 ? growthCount : 1;
        }

        @Override
        public String toString() {
            return String.format("WindowStats{class=%s, growth=%d, totalGrowth=%d, slope=%.2f}",
                className, growthCount, totalGrowth, slope);
        }
    }
}
