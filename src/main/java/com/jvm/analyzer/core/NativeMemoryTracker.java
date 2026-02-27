package com.jvm.analyzer.core;

/**
 * Native Memory Tracker - JNI bridge to JVMTI Agent
 *
 * Provides interface to native memory tracking functionality.
 * All methods are thread-safe.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class NativeMemoryTracker {

    // Load native library
    static {
        try {
            // Try to load from library path
            System.loadLibrary("jvmti_agent");
        } catch (UnsatisfiedLinkError e) {
            // Try to load from absolute path (for development)
            try {
                String libPath = System.getProperty("java.library.path") +
                                "/libjvmti_agent.dylib";
                System.load(libPath);
            } catch (Exception e2) {
                System.err.println("Warning: Native library not loaded. Running in Java-only mode.");
                System.err.println("Error: " + e.getMessage());
            }
        }
    }

    private static volatile boolean nativeAvailable = false;
    private static volatile long lastStatsTime = 0;
    private static volatile long[] cachedStats = new long[5];

    static {
        // Check if native library is available
        try {
            nativeAvailable = isAgentActive();
        } catch (UnsatisfiedLinkError e) {
            nativeAvailable = false;
        }
    }

    /**
     * Check if native agent is available and active
     */
    public static native boolean isAgentActive();

    /**
     * Get memory statistics from native agent
     * @return array of [totalAllocated, totalFreed, currentUsage, allocCount, freeCount]
     */
    public static native void getMemoryStats(long[] stats);

    /**
     * Send command to native agent
     */
    public static native void sendCommand(String command);

    /**
     * Get event queue size
     */
    public static native int getEventQueueSize();

    /**
     * Set sampling interval
     * @param interval Sample every Nth allocation (1 = all, 10 = 10%)
     */
    public static native void setSamplingInterval(int interval);

    /**
     * Check if native library is available
     */
    public static boolean isNativeAvailable() {
        return nativeAvailable;
    }

    /**
     * Get current memory statistics (thread-safe, cached)
     */
    public static synchronized MemoryStats getStats() {
        long now = System.currentTimeMillis();
        long[] stats = new long[5];

        if (nativeAvailable) {
            getMemoryStats(stats);
            lastStatsTime = now;
        } else {
            // Fallback: estimate from Runtime
            Runtime runtime = Runtime.getRuntime();
            stats[0] = runtime.totalMemory() - runtime.freeMemory();
            stats[1] = 0;
            stats[2] = stats[0];
            stats[3] = 0;
            stats[4] = 0;
        }

        cachedStats = stats;
        return new MemoryStats(stats[0], stats[1], stats[2], stats[3], stats[4], now);
    }

    /**
     * Set sampling rate
     */
    public static void setSamplingRate(int rate) {
        if (nativeAvailable) {
            setSamplingInterval(rate);
        }
    }

    /**
     * Send command to agent
     */
    public static void command(String cmd) {
        if (nativeAvailable) {
            sendCommand(cmd);
        }
    }

    /**
     * Memory statistics holder
     */
    public static class MemoryStats {
        public final long totalAllocated;
        public final long totalFreed;
        public final long currentUsage;
        public final long allocationCount;
        public final long freeCount;
        public final long timestamp;

        public MemoryStats(long totalAllocated, long totalFreed, long currentUsage,
                          long allocationCount, long freeCount, long timestamp) {
            this.totalAllocated = totalAllocated;
            this.totalFreed = totalFreed;
            this.currentUsage = currentUsage;
            this.allocationCount = allocationCount;
            this.freeCount = freeCount;
            this.timestamp = timestamp;
        }

        public long getNetAllocated() {
            return totalAllocated - totalFreed;
        }

        public double getAccuracy() {
            if (totalAllocated == 0) return 100.0;
            double diff = Math.abs(totalAllocated - totalFreed - currentUsage);
            return 100.0 - (diff / totalAllocated * 100.0);
        }

        @Override
        public String toString() {
            return String.format("MemoryStats{current=%dMB, allocated=%dMB, freed=%dMB, allocs=%d}",
                currentUsage / 1024 / 1024,
                totalAllocated / 1024 / 1024,
                totalFreed / 1024 / 1024,
                allocationCount);
        }
    }
}
