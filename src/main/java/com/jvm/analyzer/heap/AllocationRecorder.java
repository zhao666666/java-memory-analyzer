package com.jvm.analyzer.heap;

import com.jvm.analyzer.core.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Allocation Recorder - Records memory allocations using Java instrumentation
 *
 * This is a Java-level allocation recorder that works alongside
 * the JVMTI agent. It provides allocation tracking when native
 * agent is not available.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class AllocationRecorder {

    private final HeapAnalyzer heapAnalyzer;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicLong recordCount = new AtomicLong(0);
    private final AtomicLong recordBytes = new AtomicLong(0);

    // Allocation sampling
    private final AtomicLong sampleCounter = new AtomicLong(0);
    private volatile int sampleInterval;

    // Recording thread
    private volatile Thread recorderThread;

    // Memory usage history
    private final ConcurrentLinkedQueue<MemorySample> memoryHistory =
        new ConcurrentLinkedQueue<>();
    private final int maxHistorySize = 1000;

    /**
     * Create allocation recorder
     *
     * @param heapAnalyzer Parent heap analyzer
     */
    public AllocationRecorder(HeapAnalyzer heapAnalyzer) {
        this.heapAnalyzer = heapAnalyzer;
        this.sampleInterval = 100; // Sample every 100th allocation
    }

    /**
     * Start recording
     */
    public void start() {
        if (recording.getAndSet(true)) {
            return;
        }

        // Start memory sampling thread
        recorderThread = new Thread(this::samplingLoop, "AllocationRecorder");
        recorderThread.setDaemon(true);
        recorderThread.start();

        System.out.println("[AllocationRecorder] Started");
    }

    /**
     * Stop recording
     */
    public void stop() {
        if (!recording.getAndSet(false)) {
            return;
        }

        if (recorderThread != null) {
            recorderThread.interrupt();
        }

        System.out.println("[AllocationRecorder] Stopped");
    }

    /**
     * Check if recording
     */
    public boolean isRecording() {
        return recording.get();
    }

    /**
     * Sampling loop - periodically samples memory usage
     */
    private void samplingLoop() {
        long lastSampleTime = System.currentTimeMillis();

        while (recording.get()) {
            try {
                // Sample memory usage
                sampleMemory();

                // Sleep for sampling interval
                Thread.sleep(100); // 10Hz sampling

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Continue recording
            }
        }
    }

    /**
     * Sample current memory usage
     */
    private void sampleMemory() {
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();

        MemorySample sample = new MemorySample(
            System.currentTimeMillis(),
            heapUsage.getUsed(),
            heapUsage.getCommitted(),
            heapUsage.getMax()
        );

        memoryHistory.offer(sample);

        // Limit history size
        while (memoryHistory.size() > maxHistorySize) {
            memoryHistory.poll();
        }
    }

    /**
     * Record an allocation
     *
     * @param record Allocation record
     */
    public void record(AllocationRecord record) {
        if (!recording.get()) {
            return;
        }

        // Apply sampling
        long count = sampleCounter.incrementAndGet();
        if (count % sampleInterval != 0) {
            return;
        }

        // Record allocation
        recordCount.incrementAndGet();
        recordBytes.addAndGet(record.getSize());

        // Notify heap analyzer
        if (heapAnalyzer != null) {
            heapAnalyzer.recordAllocation(record);
        }
    }

    /**
     * Record allocation with current stack trace
     *
     * @param objectId Object ID
     * @param className Class name
     * @param size Size in bytes
     */
    public void recordAllocation(long objectId, String className, long size) {
        AllocationRecord record = AllocationRecord.createFromCurrent(
            objectId, className, size);
        record(record);
    }

    /**
     * Get record count
     */
    public long getRecordCount() {
        return recordCount.get();
    }

    /**
     * Get total recorded bytes
     */
    public long getRecordBytes() {
        return recordBytes.get();
    }

    /**
     * Get memory history
     */
    public List<MemorySample> getMemoryHistory() {
        return new ArrayList<>(memoryHistory);
    }

    /**
     * Get memory history size
     */
    public int getHistorySize() {
        return memoryHistory.size();
    }

    /**
     * Set sample interval
     */
    public void setSampleInterval(int interval) {
        if (interval > 0) {
            this.sampleInterval = interval;
        }
    }

    /**
     * Clear history
     */
    public void clear() {
        memoryHistory.clear();
        recordCount.set(0);
        recordBytes.set(0);
        sampleCounter.set(0);
    }

    /**
     * Memory sample holder
     */
    public static class MemorySample {
        public final long timestamp;
        public final long used;
        public final long committed;
        public final long max;

        public MemorySample(long timestamp, long used, long committed, long max) {
            this.timestamp = timestamp;
            this.used = used;
            this.committed = committed;
            this.max = max;
        }

        /**
         * Get usage percentage
         */
        public double getUsagePercent() {
            if (max <= 0) return 0;
            return (double) used / max * 100.0;
        }

        @Override
        public String toString() {
            return String.format("MemorySample{time=%d, used=%dMB, committed=%dMB, max=%dMB}",
                timestamp, used / 1024 / 1024, committed / 1024 / 1024, max / 1024 / 1024);
        }
    }

    /**
     * GC Monitor - monitors garbage collection events
     */
    public static class GcMonitor {

        private final List<GarbageCollectorMXBean> gcBeans;
        private final AtomicLong lastCollectionCount = new AtomicLong(0);
        private final AtomicLong lastCollectionTime = new AtomicLong(0);
        private volatile boolean running = false;
        private volatile Thread monitorThread;

        private final AtomicLong totalCollections = new AtomicLong(0);
        private final AtomicLong totalPauseTime = new AtomicLong(0);

        public GcMonitor() {
            this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        }

        public void start() {
            if (running) return;

            running = true;
            // Initialize counters
            for (GarbageCollectorMXBean bean : gcBeans) {
                lastCollectionCount.addAndGet(bean.getCollectionCount());
                lastCollectionTime.addAndGet(bean.getCollectionTime());
            }

            monitorThread = new Thread(this::monitorLoop, "GcMonitor");
            monitorThread.setDaemon(true);
            monitorThread.start();

            System.out.println("[GcMonitor] Started");
        }

        public void stop() {
            running = false;
            if (monitorThread != null) {
                monitorThread.interrupt();
            }
        }

        private void monitorLoop() {
            while (running) {
                try {
                    Thread.sleep(500);

                    long currentCount = 0;
                    long currentTime = 0;

                    for (GarbageCollectorMXBean bean : gcBeans) {
                        currentCount += bean.getCollectionCount();
                        currentTime += bean.getCollectionTime();
                    }

                    long deltaCount = currentCount - lastCollectionCount.get();
                    long deltaTime = currentTime - lastCollectionTime.get();

                    if (deltaCount > 0) {
                        totalCollections.addAndGet(deltaCount);
                        totalPauseTime.addAndGet(deltaTime);
                    }

                    lastCollectionCount.set(currentCount);
                    lastCollectionTime.set(currentTime);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        public GcStatistics getStatistics() {
            long count = 0;
            long time = 0;

            for (GarbageCollectorMXBean bean : gcBeans) {
                count += bean.getCollectionCount();
                time += bean.getCollectionTime();
            }

            return new GcStatistics(
                totalCollections.get(),
                totalPauseTime.get(),
                lastCollectionTime.get()
            );
        }
    }

    /**
     * GC Statistics holder
     */
    public static class GcStatistics {
        public final long collectionCount;
        public final long collectionTime;
        public final long lastGcTime;

        public GcStatistics(long collectionCount, long collectionTime, long lastGcTime) {
            this.collectionCount = collectionCount;
            this.collectionTime = collectionTime;
            this.lastGcTime = lastGcTime;
        }

        public double getAveragePauseTime() {
            return collectionCount > 0 ? (double) collectionTime / collectionCount : 0;
        }
    }
}
