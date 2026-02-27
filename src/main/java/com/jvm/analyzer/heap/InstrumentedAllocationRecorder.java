package com.jvm.analyzer.heap;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Simple allocation recorder for bytecode instrumentation mode.
 * This class is designed to be lightweight and avoid class loading issues.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class InstrumentedAllocationRecorder {

    // Singleton instance
    private static volatile InstrumentedAllocationRecorder instance = null;

    // Allocation storage
    private final ConcurrentLinkedQueue<AllocationData> allocations = new ConcurrentLinkedQueue<>();
    private final AtomicLong totalAllocations = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final ConcurrentHashMap<String, ClassStats> classStats = new ConcurrentHashMap<>();

    // Object ID generator
    private static final AtomicLong objectIdGenerator = new AtomicLong(System.currentTimeMillis() * 1000);

    /**
     * Allocation data holder
     */
    public static class AllocationData {
        public final long objectId;
        public final String className;
        public final long size;
        public final long timestamp;
        public final long threadId;
        public final String threadName;
        public final StackTraceElement[] stackTrace;

        public AllocationData(long objectId, String className, long size,
                             long timestamp, long threadId, String threadName,
                             StackTraceElement[] stackTrace) {
            this.objectId = objectId;
            this.className = className;
            this.size = size;
            this.timestamp = timestamp;
            this.threadId = threadId;
            this.threadName = threadName;
            this.stackTrace = stackTrace;
        }
    }

    /**
     * Class statistics holder
     */
    public static class ClassStats {
        public final String className;
        public final AtomicLong instanceCount = new AtomicLong(0);
        public final AtomicLong totalSize = new AtomicLong(0);

        public ClassStats(String className) {
            this.className = className;
        }

        public long getAvgSize() {
            long count = instanceCount.get();
            return count > 0 ? totalSize.get() / count : 0;
        }
    }

    /**
     * Get or create singleton instance (lazy initialization)
     */
    public static InstrumentedAllocationRecorder getInstance() {
        if (instance == null) {
            synchronized (InstrumentedAllocationRecorder.class) {
                if (instance == null) {
                    instance = new InstrumentedAllocationRecorder();
                    System.err.println("[InstrumentedAllocationRecorder] Initialized");
                }
            }
        }
        return instance;
    }

    /**
     * Record an allocation (called from instrumented constructors)
     * This method is designed to be fast and non-blocking
     */
    public static void recordAllocation(Object obj) {
        try {
            InstrumentedAllocationRecorder recorder = getInstance();

            // Generate unique object ID
            long tag = objectIdGenerator.incrementAndGet();

            // Get class info
            Class<?> clazz = obj.getClass();
            String className = clazz.getName();

            // Estimate object size
            long estimatedSize = estimateObjectSize(clazz);

            // Get thread info
            Thread currentThread = Thread.currentThread();
            long threadId = currentThread.getId();
            String threadName = currentThread.getName();

            // Capture stack trace (limited depth)
            StackTraceElement[] stackTrace = captureStackTrace();

            // Create allocation data
            AllocationData data = new AllocationData(
                tag, className, estimatedSize,
                System.currentTimeMillis(), threadId, threadName,
                stackTrace
            );

            // Store allocation
            recorder.allocations.offer(data);

            // Limit queue size
            while (recorder.allocations.size() > 10000) {
                recorder.allocations.poll();
            }

            // Update statistics
            recorder.totalAllocations.incrementAndGet();
            recorder.totalBytes.addAndGet(estimatedSize);

            // Update class stats
            ClassStats stats = recorder.classStats.get(className);
            if (stats == null) {
                stats = new ClassStats(className);
                recorder.classStats.putIfAbsent(className, stats);
                stats = recorder.classStats.get(className);
            }
            stats.instanceCount.incrementAndGet();
            stats.totalSize.addAndGet(estimatedSize);

        } catch (Exception e) {
            // Silently ignore errors to avoid interfering with application
            // e.printStackTrace();
        }
    }

    /**
     * Estimate object size
     */
    private static long estimateObjectSize(Class<?> clazz) {
        // Base object header (12-16 bytes depending on JVM)
        long size = 16;

        // Count declared fields
        try {
            java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                Class<?> fieldType = field.getType();
                if (fieldType.isPrimitive()) {
                    if (fieldType == long.class || fieldType == double.class) {
                        size += 8;
                    } else if (fieldType == int.class || fieldType == float.class) {
                        size += 4;
                    } else if (fieldType == short.class || fieldType == char.class) {
                        size += 2;
                    } else if (fieldType == boolean.class || fieldType == byte.class) {
                        size += 1;
                    }
                } else {
                    // Reference field (4-8 bytes)
                    size += 8;
                }
            }
        } catch (SecurityException e) {
            // Cannot access fields, use default estimation
        }

        // Add padding for alignment (8-byte alignment)
        size = ((size + 7) / 8) * 8;

        return size;
    }

    /**
     * Capture stack trace with limited depth
     */
    private static StackTraceElement[] captureStackTrace() {
        StackTraceElement[] fullTrace = Thread.currentThread().getStackTrace();

        // Skip internal frames:
        // [0] getStackTrace
        // [1] Thread.getStackTrace
        // [2] captureStackTrace
        // [3] recordAllocation
        // [4] <target class>.<init>

        int skipFrames = 4;
        if (fullTrace.length <= skipFrames) {
            return new StackTraceElement[0];
        }

        // Keep remaining frames
        int maxFrames = 20;
        int length = Math.min(fullTrace.length - skipFrames, maxFrames);
        StackTraceElement[] result = new StackTraceElement[length];
        System.arraycopy(fullTrace, skipFrames, result, 0, length);

        return result;
    }

    /**
     * Get recent allocations
     */
    public java.util.List<AllocationData> getRecentAllocations(int limit) {
        java.util.List<AllocationData> list = new java.util.ArrayList<>();
        int count = 0;
        for (AllocationData data : allocations) {
            list.add(data);
            if (++count >= limit) break;
        }
        return list;
    }

    /**
     * Get all class statistics
     */
    public java.util.Map<String, ClassStats> getClassStats() {
        return new java.util.concurrent.ConcurrentHashMap<>(classStats);
    }

    /**
     * Get total allocations count
     */
    public long getTotalAllocations() {
        return totalAllocations.get();
    }

    /**
     * Get total bytes allocated
     */
    public long getTotalBytes() {
        return totalBytes.get();
    }

    /**
     * Clear all data
     */
    public void clear() {
        allocations.clear();
        classStats.clear();
        totalAllocations.set(0);
        totalBytes.set(0);
    }
}
