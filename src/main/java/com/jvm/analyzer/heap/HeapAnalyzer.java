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
 * 堆分析协调器
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class HeapAnalyzer {

    //线程：监控对象分配
    private final ObjectTracker objectTracker;
    //线程：记录调用栈
    private final AllocationRecorder allocationRecorder;
    private final AllocationRecorder.GcMonitor gcMonitor;
    private final MemoryMXBean memoryMXBean;
    private final MemoryPoolMXBean[] heapMemoryPools;

    private volatile boolean analyzing = false;
    private volatile long analysisStartTime = 0;

    private final ReadWriteLock snapshotLock = new ReentrantReadWriteLock();
    private final List<MemorySnapshot> snapshots = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<AllocationRecord> recentAllocations = new ConcurrentLinkedQueue<>();

    // Statistics 统计
    private final ThreadSafeCounter allocCounter = new ThreadSafeCounter();
    private final ThreadSafeCounter.CounterMap<String> classAllocCounter = new ThreadSafeCounter.CounterMap<>();
    private final ThreadSafeCounter.CounterMap<String> threadAllocCounter =new ThreadSafeCounter.CounterMap<>();

    // JNI callback reference
    private static volatile HeapAnalyzer instance = null;

    // Object ID generator for instrumentation mode
    private static final AtomicLong objectIdGenerator = new AtomicLong(System.currentTimeMillis());

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

        // Set instance for JNI callback
        instance = this;
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

    // ========================================================================
    // JNI Callback Methods (called from JVMTI Agent)
    // ========================================================================

    /**
     * Callback from JVMTI Agent for object allocation events
     * Called by native code via JNI
     *
     * @param tag       Object tag (unique ID)
     * @param className Class name of allocated object
     * @param size      Size in bytes
     * @param threadId  Thread ID that allocated the object
     * @param threadName Thread name
     * @param stackTrace Stack trace elements (format: "class.method(file:line)")
     */
    public static void onObjectAlloc(long tag, String className, long size,
                                      long threadId, String threadName, String stackTrace) {
        if (instance != null) {
            // Always record allocation, regardless of analyzing state
            // This allows capturing allocations even before startAnalysis() is called
            AllocationRecord record = new AllocationRecord(
                tag,
                className,
                size,
                System.currentTimeMillis(),
                threadId,
                threadName,
                parseStackTrace(stackTrace)
            );
            instance.recordAllocation(record);
        }
    }

    /**
     * Parse stack trace string to array
     */
    private static StackTraceElement[] parseStackTrace(String trace) {
        if (trace == null || trace.isEmpty()) {
            return new StackTraceElement[0];
        }
        // Format: "class.method(file:line);class.method(file:line);..."
        String[] parts = trace.split(";");
        StackTraceElement[] elements = new StackTraceElement[parts.length];
        for (int i = 0; i < parts.length; i++) {
            // Parse "class.method(file:line)"
            String part = parts[i].trim();
            int methodStart = part.lastIndexOf('.');
            int fileStart = part.lastIndexOf('(');
            int lineStart = part.lastIndexOf(':');
            int lineEnd = part.lastIndexOf(')');

            if (methodStart > 0 && fileStart > 0 && lineStart > 0 && lineEnd > lineStart) {
                String declaringClass = part.substring(0, methodStart);
                String methodName = part.substring(methodStart + 1, fileStart);
                String fileName = part.substring(fileStart + 1, lineStart);
                int lineNumber = Integer.parseInt(part.substring(lineStart + 1, lineEnd));
                elements[i] = new StackTraceElement(declaringClass, methodName, fileName, lineNumber);
            }
        }
        return elements;
    }

    // ========================================================================
    // Instrumentation Callback Methods (called from AllocationClassTransformer)
    // ========================================================================

    /**
     * Callback from bytecode instrumentation for object allocation events
     * Called by instrumented constructors via ASM
     *
     * This method is invoked at the end of object constructor execution,
     * allowing us to track object allocations without relying on JVMTI events.
     *
     * @param obj  The newly allocated object
     */
    public static void onInstrumentedAllocation(Object obj) {
        if (instance != null) {
            // Generate unique object ID
            long tag = objectIdGenerator.incrementAndGet();

            // Get object class info
            Class<?> clazz = obj.getClass();
            String className = clazz.getName();

            // Estimate object size (simplified estimation)
            long estimatedSize = estimateObjectSize(obj);

            // Get thread info
            Thread currentThread = Thread.currentThread();
            long threadId = currentThread.getId();
            String threadName = currentThread.getName();

            // Capture stack trace
            StackTraceElement[] stackTrace = captureInstrumentationStackTrace();

            // Create allocation record
            AllocationRecord record = new AllocationRecord(
                tag,
                className,
                estimatedSize,
                System.currentTimeMillis(),
                threadId,
                threadName,
                stackTrace
            );

            // Record the allocation
            instance.recordAllocation(record);
        }
    }

    /**
     * Capture stack trace for instrumentation callback
     * Skip the first few frames that are internal to the instrumentation
     */
    private static StackTraceElement[] captureInstrumentationStackTrace() {
        // Get current stack trace
        StackTraceElement[] fullTrace = Thread.currentThread().getStackTrace();

        // Skip internal frames:
        // [0] getStackTrace (native)
        // [1] Thread.getStackTrace
        // [2] HeapAnalyzer.captureInstrumentationStackTrace
        // [3] HeapAnalyzer.onInstrumentedAllocation
        // [4] <target class>.<init> (this is what we want to keep)

        int skipFrames = 4;
        if (fullTrace.length <= skipFrames) {
            return new StackTraceElement[0];
        }

        // Keep remaining frames (the actual application call stack)
        StackTraceElement[] result = new StackTraceElement[fullTrace.length - skipFrames];
        System.arraycopy(fullTrace, skipFrames, result, 0, result.length);

        // Limit to 20 frames for performance
        if (result.length > 20) {
            StackTraceElement[] limited = new StackTraceElement[20];
            System.arraycopy(result, 0, limited, 0, 20);
            return limited;
        }

        return result;
    }

    /**
     * Estimate object size using simple heuristics
     * This is a simplified estimation - for accurate sizing, use JVMTI
     */
    private static long estimateObjectSize(Object obj) {
        if (obj == null) {
            return 0;
        }

        Class<?> clazz = obj.getClass();

        // Base object header (12-16 bytes depending on JVM)
        long size = 16;

        // Add size for primitive fields
        try {
            // Count declared fields (simplified estimation)
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
}