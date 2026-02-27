package com.jvm.analyzer.heap;

import com.jvm.analyzer.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * Object Tracker - Tracks object allocations and lifecycle
 *
 * Maintains a registry of tracked objects with their allocation information.
 * Supports automatic cleanup when objects are garbage collected.
 *
 * Thread-safe implementation using concurrent data structures.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class ObjectTracker {

    // Object registry: objectId -> AllocationRecord
    private final ConcurrentHashMap<Long, AllocationRecord> objectRegistry =
        new ConcurrentHashMap<>();

    // Class statistics: className -> ClassInfo
    private final ConcurrentHashMap<String, ClassInfo> classStats =
        new ConcurrentHashMap<>();

    // Allocation site statistics: site -> SiteInfo
    private final ConcurrentHashMap<String, SiteInfo> siteStats =
        new ConcurrentHashMap<>();

    // Reference queue for detecting GC'd objects
    private final AtomicLong trackedCount = new AtomicLong(0);
    private final AtomicLong totalTracked = new AtomicLong(0);
    private final AtomicLong totalFreed = new AtomicLong(0);

    // Size limits
    private final int maxTrackedObjects;
    private final long cleanupIntervalMs;

    // Cleanup thread
    private volatile Thread cleanupThread;
    private volatile boolean running = true;

    // Lock for write operations
    private final ReadWriteLock registryLock = new ReentrantReadWriteLock();

    /**
     * Create object tracker with default settings
     */
    public ObjectTracker() {
        this(100000, 5000);
    }

    /**
     * Create object tracker
     *
     * @param maxTrackedObjects Maximum number of objects to track
     * @param cleanupIntervalMs Cleanup interval in milliseconds
     */
    public ObjectTracker(int maxTrackedObjects, long cleanupIntervalMs) {
        this.maxTrackedObjects = maxTrackedObjects;
        this.cleanupIntervalMs = cleanupIntervalMs;
        startCleanupThread();
    }

    /**
     * Start cleanup thread
     */
    private void startCleanupThread() {
        cleanupThread = new Thread(this::cleanupLoop, "ObjectTracker-Cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * Cleanup loop
     */
    private void cleanupLoop() {
        while (running) {
            try {
                Thread.sleep(cleanupIntervalMs);
                performCleanup();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Log but continue
                System.err.println("[ObjectTracker] Cleanup error: " + e.getMessage());
            }
        }
    }

    /**
     * Perform cleanup of old entries
     */
    private void performCleanup() {
        // Remove entries that exceed size limit (oldest first)
        while (objectRegistry.size() > maxTrackedObjects) {
            Map.Entry<Long, AllocationRecord> oldest = null;
            long oldestTime = Long.MAX_VALUE;

            for (Map.Entry<Long, AllocationRecord> entry : objectRegistry.entrySet()) {
                if (entry.getValue().getTimestamp() < oldestTime) {
                    oldestTime = entry.getValue().getTimestamp();
                    oldest = entry;
                }
            }

            if (oldest != null) {
                remove(oldest.getKey());
            } else {
                break;
            }
        }
    }

    /**
     * Track an object allocation
     *
     * @param record Allocation record
     */
    public void track(AllocationRecord record) {
        if (record == null) {
            return;
        }

        registryLock.writeLock().lock();
        try {
            // Check if already tracked
            if (objectRegistry.containsKey(record.getObjectId())) {
                return;
            }

            // Add to registry
            objectRegistry.put(record.getObjectId(), record);
            trackedCount.incrementAndGet();
            totalTracked.incrementAndGet();

            // Update class statistics
            updateClassStats(record.getClassName(), record.getSize());

            // Update site statistics
            updateSiteStats(record.getAllocationSite(), record.getSize());

        } finally {
            registryLock.writeLock().unlock();
        }
    }

    /**
     * Update class statistics
     */
    private void updateClassStats(String className, long size) {
        classStats.compute(className, (key, info) -> {
            if (info == null) {
                return new ClassInfo(className, 1, size);
            }
            return new ClassInfo(
                className,
                info.instanceCount + 1,
                info.totalSize + size
            );
        });
    }

    /**
     * Update site statistics
     */
    private void updateSiteStats(String site, long size) {
        siteStats.compute(site, (key, info) -> {
            if (info == null) {
                return new SiteInfo(site, 1, size);
            }
            return new SiteInfo(
                site,
                info.allocationCount + 1,
                info.totalSize + size
            );
        });
    }

    /**
     * Remove tracked object
     *
     * @param objectId Object ID
     * @return Removed allocation record, or null if not found
     */
    public AllocationRecord remove(long objectId) {
        registryLock.writeLock().lock();
        try {
            AllocationRecord record = objectRegistry.remove(objectId);
            if (record != null) {
                trackedCount.decrementAndGet();
                totalFreed.incrementAndGet();

                // Update class statistics
                classStats.compute(record.getClassName(), (key, info) -> {
                    if (info == null || info.instanceCount <= 1) {
                        return null;
                    }
                    return new ClassInfo(
                        info.className,
                        info.instanceCount - 1,
                        info.totalSize - record.getSize()
                    );
                });
            }
            return record;
        } finally {
            registryLock.writeLock().unlock();
        }
    }

    /**
     * Get tracked object
     *
     * @param objectId Object ID
     * @return Allocation record, or null if not found
     */
    public AllocationRecord get(long objectId) {
        return objectRegistry.get(objectId);
    }

    /**
     * Check if object is tracked
     *
     * @param objectId Object ID
     * @return true if tracked
     */
    public boolean isTracked(long objectId) {
        return objectRegistry.containsKey(objectId);
    }

    /**
     * Get tracked object count
     */
    public long getTrackedCount() {
        return trackedCount.get();
    }

    /**
     * Get total tracked (cumulative)
     */
    public long getTotalTracked() {
        return totalTracked.get();
    }

    /**
     * Get total freed
     */
    public long getTotalFreed() {
        return totalFreed.get();
    }

    /**
     * Get all tracked objects
     */
    public Collection<AllocationRecord> getAllTracked() {
        return Collections.unmodifiableCollection(objectRegistry.values());
    }

    /**
     * Get class statistics
     */
    public Map<String, ClassInfo> getClassStatistics() {
        return Collections.unmodifiableMap(classStats);
    }

    /**
     * Get site statistics
     */
    public Map<String, SiteInfo> getSiteStatistics() {
        return Collections.unmodifiableMap(siteStats);
    }

    /**
     * Get top allocating classes
     *
     * @param limit Number of results
     */
    public List<ClassInfo> getTopClasses(int limit) {
        List<ClassInfo> list = new ArrayList<>(classStats.values());
        list.sort(Comparator.comparingLong((ClassInfo c) -> c.totalSize).reversed());
        return list.subList(0, Math.min(limit, list.size()));
    }

    /**
     * Get top allocation sites
     *
     * @param limit Number of results
     */
    public List<SiteInfo> getTopSites(int limit) {
        List<SiteInfo> list = new ArrayList<>(siteStats.values());
        list.sort(Comparator.comparingLong((SiteInfo s) -> s.totalSize).reversed());
        return list.subList(0, Math.min(limit, list.size()));
    }

    /**
     * Get objects by class name
     *
     * @param className Class name
     * @return List of allocation records
     */
    public List<AllocationRecord> getObjectsByClass(String className) {
        List<AllocationRecord> result = new ArrayList<>();
        for (AllocationRecord record : objectRegistry.values()) {
            if (record.getClassName().equals(className)) {
                result.add(record);
            }
        }
        return result;
    }

    /**
     * Get objects allocated after timestamp
     *
     * @param timestamp Timestamp in milliseconds
     * @return List of allocation records
     */
    public List<AllocationRecord> getObjectsAfter(long timestamp) {
        List<AllocationRecord> result = new ArrayList<>();
        for (AllocationRecord record : objectRegistry.values()) {
            if (record.getTimestamp() >= timestamp) {
                result.add(record);
            }
        }
        return result;
    }

    /**
     * Get objects older than ageMs milliseconds
     *
     * @param ageMs Age in milliseconds
     * @return List of allocation records
     */
    public List<AllocationRecord> getObjectsOlderThan(long ageMs) {
        long cutoff = System.currentTimeMillis() - ageMs;
        List<AllocationRecord> result = new ArrayList<>();
        for (AllocationRecord record : objectRegistry.values()) {
            if (record.getTimestamp() <= cutoff) {
                result.add(record);
            }
        }
        return result;
    }

    /**
     * Clear all tracked objects
     */
    public void clear() {
        registryLock.writeLock().lock();
        try {
            objectRegistry.clear();
            classStats.clear();
            siteStats.clear();
            trackedCount.set(0);
        } finally {
            registryLock.writeLock().unlock();
        }
    }

    /**
     * Stop the tracker
     */
    public void stop() {
        running = false;
        if (cleanupThread != null) {
            cleanupThread.interrupt();
        }
    }

    /**
     * Class information holder
     */
    public static class ClassInfo {
        public final String className;
        public final int instanceCount;
        public final long totalSize;
        public final long avgSize;

        public ClassInfo(String className, int instanceCount, long totalSize) {
            this.className = className;
            this.instanceCount = instanceCount;
            this.totalSize = totalSize;
            this.avgSize = instanceCount > 0 ? totalSize / instanceCount : 0;
        }

        @Override
        public String toString() {
            return String.format("%s: %d instances, %d bytes (avg: %d)",
                className, instanceCount, totalSize, avgSize);
        }
    }

    /**
     * Allocation site information holder
     */
    public static class SiteInfo {
        public final String site;
        public final long allocationCount;
        public final long totalSize;
        public final long avgSize;

        public SiteInfo(String site, long allocationCount, long totalSize) {
            this.site = site;
            this.allocationCount = allocationCount;
            this.totalSize = totalSize;
            this.avgSize = allocationCount > 0 ? totalSize / allocationCount : 0;
        }

        @Override
        public String toString() {
            return String.format("%s: %d allocs, %d bytes",
                site, allocationCount, totalSize);
        }
    }
}
