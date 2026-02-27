package com.jvm.analyzer.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * Memory Snapshot - Captures a point-in-time view of heap memory
 *
 * Thread-safe implementation using concurrent data structures.
 * Supports snapshot comparison for leak detection.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class MemorySnapshot implements Comparable<MemorySnapshot> {

    private final long snapshotId;
    private final long timestamp;
    private final long threadId;
    private final String threadName;
    private final Map<String, ClassStats> classStats;
    private final Map<Long, AllocationRecord> allocations;
    private final long totalHeapUsed;
    private final long totalHeapCommitted;
    private final long totalHeapMax;

    private static final AtomicLong idGenerator = new AtomicLong(0);
    private static final ReadWriteLock snapshotLock = new ReentrantReadWriteLock();

    private MemorySnapshot(Builder builder) {
        this.snapshotId = builder.snapshotId;
        this.timestamp = builder.timestamp;
        this.threadId = builder.threadId;
        this.threadName = builder.threadName;
        this.classStats = new ConcurrentHashMap<>(builder.classStats);
        this.allocations = new ConcurrentHashMap<>(builder.allocations);
        this.totalHeapUsed = builder.totalHeapUsed;
        this.totalHeapCommitted = builder.totalHeapCommitted;
        this.totalHeapMax = builder.totalHeapMax;
    }

    /**
     * Get snapshot ID
     */
    public long getSnapshotId() {
        return snapshotId;
    }

    /**
     * Get timestamp
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get class statistics
     */
    public Map<String, ClassStats> getClassStats() {
        return Collections.unmodifiableMap(classStats);
    }

    /**
     * Get allocations
     */
    public Map<Long, AllocationRecord> getAllocations() {
        return Collections.unmodifiableMap(allocations);
    }

    /**
     * Get total heap used
     */
    public long getTotalHeapUsed() {
        return totalHeapUsed;
    }

    /**
     * Get total heap committed
     */
    public long getTotalHeapCommitted() {
        return totalHeapCommitted;
    }

    /**
     * Get total heap max
     */
    public long getTotalHeapMax() {
        return totalHeapMax;
    }

    /**
     * Get snapshot age in milliseconds
     */
    public long getAge() {
        return System.currentTimeMillis() - timestamp;
    }

    /**
     * Compare with another snapshot
     */
    public SnapshotDiff compare(MemorySnapshot other) {
        return new SnapshotDiff(this, other);
    }

    @Override
    public int compareTo(MemorySnapshot other) {
        return Long.compare(this.timestamp, other.timestamp);
    }

    @Override
    public String toString() {
        return String.format("MemorySnapshot{id=%d, time=%d, classes=%d, allocs=%d, heap=%dMB}",
            snapshotId, timestamp, classStats.size(), allocations.size(),
            totalHeapUsed / 1024 / 1024);
    }

    /**
     * Snapshot difference result
     */
    public static class SnapshotDiff {
        public final long baseSnapshotId;
        public final long currentSnapshotId;
        public final long timeDelta;
        public final long heapDelta;
        public final Map<String, ClassDiff> classDiffs;
        public final List<AllocationRecord> newAllocations;
        public final List<Long> freedAllocations;

        public SnapshotDiff(MemorySnapshot base, MemorySnapshot current) {
            this.baseSnapshotId = base.snapshotId;
            this.currentSnapshotId = current.snapshotId;
            this.timeDelta = current.timestamp - base.timestamp;
            this.heapDelta = current.totalHeapUsed - base.totalHeapUsed;

            this.classDiffs = new ConcurrentHashMap<>();
            for (Map.Entry<String, ClassStats> entry : current.classStats.entrySet()) {
                String className = entry.getKey();
                ClassStats currentStats = entry.getValue();
                ClassStats baseStats = base.classStats.get(className);

                if (baseStats != null) {
                    classDiffs.put(className, new ClassDiff(
                        className,
                        currentStats.instanceCount - baseStats.instanceCount,
                        currentStats.totalSize - baseStats.totalSize
                    ));
                } else {
                    classDiffs.put(className, new ClassDiff(
                        className,
                        currentStats.instanceCount,
                        currentStats.totalSize
                    ));
                }
            }

            this.newAllocations = new ArrayList<>();
            this.freedAllocations = new ArrayList<>();

            for (Map.Entry<Long, AllocationRecord> entry : current.allocations.entrySet()) {
                if (!base.allocations.containsKey(entry.getKey())) {
                    newAllocations.add(entry.getValue());
                }
            }

            for (Long key : base.allocations.keySet()) {
                if (!current.allocations.containsKey(key)) {
                    freedAllocations.add(key);
                }
            }
        }

        /**
         * Get potential memory leaks (classes with growing instance count)
         */
        public List<ClassDiff> getPotentialLeaks(int minGrowth) {
            List<ClassDiff> leaks = new ArrayList<>();
            for (ClassDiff diff : classDiffs.values()) {
                if (diff.instanceDelta >= minGrowth) {
                    leaks.add(diff);
                }
            }
            leaks.sort(Comparator.comparingLong(d -> -d.instanceDelta));
            return leaks;
        }

        @Override
        public String toString() {
            return String.format("SnapshotDiff{time=%dms, heap=%+dMB, classes=%d}",
                timeDelta, heapDelta / 1024 / 1024, classDiffs.size());
        }
    }

    /**
     * Class-level statistics
     */
    public static class ClassStats {
        public final String className;
        public final int instanceCount;
        public final long totalSize;
        public final long avgSize;

        public ClassStats(String className, int instanceCount, long totalSize) {
            this.className = className;
            this.instanceCount = instanceCount;
            this.totalSize = totalSize;
            this.avgSize = instanceCount > 0 ? totalSize / instanceCount : 0;
        }

        @Override
        public String toString() {
            return String.format("%s: %d instances, %d bytes",
                className, instanceCount, totalSize);
        }
    }

    /**
     * Class difference for leak detection
     */
    public static class ClassDiff {
        public final String className;
        public final int instanceDelta;
        public final long sizeDelta;

        public ClassDiff(String className, int instanceDelta, long sizeDelta) {
            this.className = className;
            this.instanceDelta = instanceDelta;
            this.sizeDelta = sizeDelta;
        }

        @Override
        public String toString() {
            return String.format("%s: %+d instances, %+d bytes",
                className, instanceDelta, sizeDelta);
        }
    }

    /**
     * Builder for MemorySnapshot
     */
    public static class Builder {
        private long snapshotId;
        private long timestamp;
        private long threadId;
        private String threadName;
        private Map<String, ClassStats> classStats = new ConcurrentHashMap<>();
        private Map<Long, AllocationRecord> allocations = new ConcurrentHashMap<>();
        private long totalHeapUsed;
        private long totalHeapCommitted;
        private long totalHeapMax;

        public Builder() {
            this.snapshotId = idGenerator.incrementAndGet();
            this.timestamp = System.currentTimeMillis();
            this.threadId = Thread.currentThread().getId();
            this.threadName = Thread.currentThread().getName();
        }

        public Builder setSnapshotId(long id) {
            this.snapshotId = id;
            return this;
        }

        public Builder setTimestamp(long ts) {
            this.timestamp = ts;
            return this;
        }

        public Builder setThreadId(long id) {
            this.threadId = id;
            return this;
        }

        public Builder setThreadName(String name) {
            this.threadName = name;
            return this;
        }

        public Builder setClassStats(Map<String, ClassStats> stats) {
            this.classStats = new ConcurrentHashMap<>(stats);
            return this;
        }

        public Builder addClassStat(String className, int count, long size) {
            this.classStats.put(className, new ClassStats(className, count, size));
            return this;
        }

        public Builder setAllocations(Map<Long, AllocationRecord> allocs) {
            this.allocations = new ConcurrentHashMap<>(allocs);
            return this;
        }

        public Builder addAllocation(AllocationRecord record) {
            this.allocations.put(record.getObjectId(), record);
            return this;
        }

        public Builder setTotalHeapUsed(long used) {
            this.totalHeapUsed = used;
            return this;
        }

        public Builder setTotalHeapCommitted(long committed) {
            this.totalHeapCommitted = committed;
            return this;
        }

        public Builder setTotalHeapMax(long max) {
            this.totalHeapMax = max;
            return this;
        }

        public MemorySnapshot build() {
            return new MemorySnapshot(this);
        }
    }
}
