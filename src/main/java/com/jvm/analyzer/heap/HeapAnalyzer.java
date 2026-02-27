package com.jvm.analyzer.heap;

import com.jvm.analyzer.core.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.function.*;

/**
 * Heap Analyzer - Analyzes heap memory usage and object allocations
 *
 * Provides comprehensive heap analysis including:
 * - Memory allocation tracking
 * - Object lifecycle monitoring
 * - GC analysis
 * - Heap histogram generation
 *
 * All operations are thread-safe.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class HeapAnalyzer {

    private final ObjectTracker objectTracker;
    private final AllocationRecorder allocationRecorder;
    private final AllocationRecorder.GcMonitor gcMonitor;
    private final MemoryMXBean memoryMXBean;
    private final MemoryPoolMXBean[] heapMemoryPools;

    private volatile boolean analyzing = false;
    private volatile long analysisStartTime = 0;

    private final ReadWriteLock snapshotLock = new ReentrantReadWriteLock();
    private final List<MemorySnapshot> snapshots = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<AllocationRecord> recentAllocations =
        new ConcurrentLinkedQueue<>();

    // Statistics
    private final ThreadSafeCounter allocCounter = new ThreadSafeCounter();
    private final ThreadSafeCounter.CounterMap<String> classAllocCounter =
        new ThreadSafeCounter.CounterMap<>();
    private final ThreadSafeCounter.CounterMap<String> threadAllocCounter =
        new ThreadSafeCounter.CounterMap<>();

    /**
     * Create heap analyzer
     */
    public HeapAnalyzer() {
        this.objectTracker = new ObjectTracker();
        this.allocationRecorder = new AllocationRecorder(this);
        this.gcMonitor = new AllocationRecorder.GcMonitor();

        this.memoryMXBean = ManagementFactory.getMemoryMXBean();

        // Get heap memory pools
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        List<MemoryPoolMXBean> heapPools = new ArrayList<>();
        for (MemoryPoolMXBean pool : pools) {
            if (pool.getType() == MemoryType.HEAP) {
                heapPools.add(pool);
            }
        }
        this.heapMemoryPools = heapPools.toArray(new MemoryPoolMXBean[0]);
    }

    /**
     * Start analysis
     */
    public void startAnalysis() {
        if (analyzing) {
            return;
        }

        analyzing = true;
        analysisStartTime = System.currentTimeMillis();

        // Start GC monitoring
        gcMonitor.start();

        // Register allocation listener
        allocationRecorder.start();

        System.out.println("[HeapAnalyzer] Analysis started");
    }

    /**
     * Stop analysis
     */
    public void stopAnalysis() {
        if (!analyzing) {
            return;
        }

        analyzing = false;

        // Stop GC monitoring
        gcMonitor.stop();

        // Stop allocation recording
        allocationRecorder.stop();

        System.out.println("[HeapAnalyzer] Analysis stopped");
    }

    /**
     * Check if analysis is running
     */
    public boolean isAnalyzing() {
        return analyzing;
    }

    /**
     * Take a memory snapshot
     */
    public MemorySnapshot takeSnapshot() {
        snapshotLock.writeLock().lock();
        try {
            MemorySnapshot snapshot = createSnapshot();
            snapshots.add(snapshot);

            // Keep only last 100 snapshots
            while (snapshots.size() > 100) {
                snapshots.remove(0);
            }

            return snapshot;
        } finally {
            snapshotLock.writeLock().unlock();
        }
    }

    /**
     * Create snapshot
     */
    private MemorySnapshot createSnapshot() {
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();

        MemorySnapshot.Builder builder = new MemorySnapshot.Builder()
            .setTotalHeapUsed(heapUsage.getUsed())
            .setTotalHeapCommitted(heapUsage.getCommitted())
            .setTotalHeapMax(heapUsage.getMax());

        // Add class statistics
        Map<String, MemorySnapshot.ClassStats> classStats = getClassStatistics();
        builder.setClassStats(classStats);

        // Add recent allocations
        for (AllocationRecord record : recentAllocations) {
            builder.addAllocation(record);
        }

        return builder.build();
    }

    /**
     * Get class statistics from heap
     */
    public Map<String, MemorySnapshot.ClassStats> getClassStatistics() {
        Map<String, MemorySnapshot.ClassStats> stats = new ConcurrentHashMap<>();

        // Use object tracker for class statistics
        Map<String, ObjectTracker.ClassInfo> trackerStats =
            objectTracker.getClassStatistics();

        for (Map.Entry<String, ObjectTracker.ClassInfo> entry : trackerStats.entrySet()) {
            String className = entry.getKey();
            ObjectTracker.ClassInfo info = entry.getValue();
            stats.put(className, new MemorySnapshot.ClassStats(
                className, info.instanceCount, info.totalSize));
        }

        return stats;
    }

    /**
     * Get heap memory usage
     */
    public MemoryUsage getHeapMemoryUsage() {
        return memoryMXBean.getHeapMemoryUsage();
    }

    /**
     * Get heap memory pool usages
     */
    public Map<String, MemoryUsage> getHeapPoolUsages() {
        Map<String, MemoryUsage> usages = new LinkedHashMap<>();
        for (MemoryPoolMXBean pool : heapMemoryPools) {
            usages.put(pool.getName(), pool.getUsage());
        }
        return usages;
    }

    /**
     * Get allocation statistics
     */
    public AllocationStats getAllocationStats() {
        return new AllocationStats(
            allocCounter.getCount(),
            allocCounter.getSum(),
            classAllocCounter.getSortedBySum(10),
            threadAllocCounter.getSortedBySum(10)
        );
    }

    /**
     * Record allocation (called from AllocationRecorder)
     */
    public void recordAllocation(AllocationRecord record) {
        recentAllocations.offer(record);

        // Limit queue size
        while (recentAllocations.size() > 10000) {
            recentAllocations.poll();
        }

        // Update statistics
        allocCounter.add(record.getSize());
        classAllocCounter.add(record.getClassName(), record.getSize());
        threadAllocCounter.add(record.getThreadName(), record.getSize());

        // Track object
        objectTracker.track(record);
    }

    /**
     * Get recent allocations
     */
    public List<AllocationRecord> getRecentAllocations(int limit) {
        List<AllocationRecord> list = new ArrayList<>();
        int count = 0;
        for (AllocationRecord record : recentAllocations) {
            list.add(record);
            if (++count >= limit) break;
        }
        return list;
    }

    /**
     * Get all snapshots
     */
    public List<MemorySnapshot> getSnapshots() {
        return new ArrayList<>(snapshots);
    }

    /**
     * Get latest snapshot
     */
    public MemorySnapshot getLatestSnapshot() {
        if (snapshots.isEmpty()) {
            return null;
        }
        return snapshots.get(snapshots.size() - 1);
    }

    /**
     * Compare two snapshots
     */
    public MemorySnapshot.SnapshotDiff compareSnapshots(long baseId, long currentId) {
        MemorySnapshot base = null;
        MemorySnapshot current = null;

        for (MemorySnapshot snapshot : snapshots) {
            if (snapshot.getSnapshotId() == baseId) {
                base = snapshot;
            }
            if (snapshot.getSnapshotId() == currentId) {
                current = snapshot;
            }
        }

        if (base != null && current != null) {
            return base.compare(current);
        }

        return null;
    }

    /**
     * Get GC statistics
     */
    public AllocationRecorder.GcStatistics getGcStatistics() {
        return gcMonitor.getStatistics();
    }

    /**
     * Clear all data
     */
    public void clear() {
        snapshots.clear();
        recentAllocations.clear();
        allocCounter.reset();
        classAllocCounter.clear();
        threadAllocCounter.clear();
        objectTracker.clear();
    }

    /**
     * Get object tracker
     */
    public ObjectTracker getObjectTracker() {
        return objectTracker;
    }

    /**
     * Allocation statistics holder
     */
    public static class AllocationStats {
        public final long allocationCount;
        public final long totalBytes;
        public final List<Map.Entry<String, Long>> topClasses;
        public final List<Map.Entry<String, Long>> topThreads;

        public AllocationStats(long allocationCount, long totalBytes,
                              List<Map.Entry<String, Long>> topClasses,
                              List<Map.Entry<String, Long>> topThreads) {
            this.allocationCount = allocationCount;
            this.totalBytes = totalBytes;
            this.topClasses = topClasses;
            this.topThreads = topThreads;
        }
    }
}