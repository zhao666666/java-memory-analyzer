package com.jvm.analyzer.leak;

import com.jvm.analyzer.core.*;
import com.jvm.analyzer.heap.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * Leak Detector - Detects potential memory leaks
 *
 * Uses multiple detection strategies:
 * - Object age analysis (long-lived objects)
 * - Growth pattern detection
 * - Time window analysis
 * - Reference chain analysis
 *
 * Thread-safe implementation.
 *
 * 内存泄漏检测器
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class LeakDetector {

    // Detection thresholds
    private static final long DEFAULT_AGE_THRESHOLD_MS = 60000; // 1 minute
    private static final int DEFAULT_GROWTH_THRESHOLD = 100; // 100 instances
    private static final int DEFAULT_WINDOW_SIZE = 10; // 10 snapshots

    private final long ageThresholdMs;
    private final int growthThreshold;
    private final int windowSize;

    private final ObjectTracker objectTracker;
    private final TimeWindowAnalyzer windowAnalyzer;

    private final AtomicBoolean detecting = new AtomicBoolean(false);
    private final AtomicLong detectionCount = new AtomicLong(0);

    private final ReadWriteLock resultsLock = new ReentrantReadWriteLock();
    private final List<LeakReport> leakReports = new CopyOnWriteArrayList<>();

    // Listeners
    private final List<LeakListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Create leak detector with default settings
     */
    public LeakDetector(ObjectTracker objectTracker) {
        this(objectTracker, DEFAULT_AGE_THRESHOLD_MS, DEFAULT_GROWTH_THRESHOLD, DEFAULT_WINDOW_SIZE);
    }

    /**
     * Create leak detector
     *
     * @param objectTracker Object tracker instance
     * @param ageThresholdMs Age threshold in milliseconds
     * @param growthThreshold Instance growth threshold
     * @param windowSize Time window size (number of snapshots)
     */
    public LeakDetector(ObjectTracker objectTracker, long ageThresholdMs,
                       int growthThreshold, int windowSize) {
        this.objectTracker = objectTracker;
        this.ageThresholdMs = ageThresholdMs;
        this.growthThreshold = growthThreshold;
        this.windowSize = windowSize;
        this.windowAnalyzer = new TimeWindowAnalyzer(windowSize);
    }

    /**
     * Start leak detection
     */
    public void start() {
        detecting.set(true);
        System.out.println("[LeakDetector] Started with ageThreshold=" + ageThresholdMs + "ms");
    }

    /**
     * Stop leak detection
     */
    public void stop() {
        detecting.set(false);
        System.out.println("[LeakDetector] Stopped");
    }

    /**
     * Check if detection is running
     */
    public boolean isDetecting() {
        return detecting.get();
    }

    /**
     * Run leak detection
     *
     * @return Leak report
     */
    public LeakReport detect() {
        if (!detecting.get()) {
            return null;
        }

        List<LeakCandidate> candidates = new ArrayList<>();

        // Strategy 1: Age-based detection
        candidates.addAll(detectByAge());

        // Strategy 2: Growth pattern detection
        candidates.addAll(detectByGrowth());

        // Strategy 3: Window-based analysis
        candidates.addAll(detectByWindow());

        // Create report
        LeakReport report = createReport(candidates);

        if (!candidates.isEmpty()) {
            detectionCount.incrementAndGet();
            resultsLock.writeLock().lock();
            try {
                leakReports.add(report);
                // Keep last 50 reports
                while (leakReports.size() > 50) {
                    leakReports.remove(0);
                }
            } finally {
                resultsLock.writeLock().unlock();
            }

            // Notify listeners
            for (LeakListener listener : listeners) {
                try {
                    listener.onLeakDetected(report);
                } catch (Exception e) {
                    // Ignore listener errors
                }
            }
        }

        return report;
    }

    /**
     * Detect leaks by object age
     */
    private List<LeakCandidate> detectByAge() {
        List<LeakCandidate> candidates = new ArrayList<>();

        // Find objects older than threshold
        List<AllocationRecord> oldObjects = objectTracker.getObjectsOlderThan(ageThresholdMs);

        // Group by class
        Map<String, List<AllocationRecord>> byClass = new HashMap<>();
        for (AllocationRecord record : oldObjects) {
            byClass.computeIfAbsent(record.getClassName(), k -> new ArrayList<>()).add(record);
        }

        // Create candidates for classes with many old objects
        for (Map.Entry<String, List<AllocationRecord>> entry : byClass.entrySet()) {
            List<AllocationRecord> records = entry.getValue();
            if (records.size() >= growthThreshold) {
                candidates.add(new LeakCandidate(
                    entry.getKey(),
                    records.size(),
                    records.stream().mapToLong(AllocationRecord::getSize).sum(),
                    LeakType.AGE_BASED,
                    records.get(0).getAllocationSite(),
                    records,
                    String.format("Found %d objects older than %d seconds",
                        records.size(), ageThresholdMs / 1000)
                ));
            }
        }

        return candidates;
    }

    /**
     * Detect leaks by growth pattern
     */
    private List<LeakCandidate> detectByGrowth() {
        List<LeakCandidate> candidates = new ArrayList<>();

        Map<String, ObjectTracker.ClassInfo> classStats = objectTracker.getClassStatistics();

        for (Map.Entry<String, ObjectTracker.ClassInfo> entry : classStats.entrySet()) {
            ObjectTracker.ClassInfo info = entry.getValue();

            // Check if instance count exceeds threshold
            if (info.instanceCount >= growthThreshold * 2) {
                List<AllocationRecord> records = objectTracker.getObjectsByClass(info.className);

                candidates.add(new LeakCandidate(
                    info.className,
                    info.instanceCount,
                    info.totalSize,
                    LeakType.GROWTH_BASED,
                    getTopAllocationSite(records),
                    records,
                    String.format("Class has %d instances (%d bytes)",
                        info.instanceCount, info.totalSize)
                ));
            }
        }

        return candidates;
    }

    /**
     * Detect leaks by time window analysis
     */
    private List<LeakCandidate> detectByWindow() {
        List<LeakCandidate> candidates = new ArrayList<>();

        // Get current class stats
        Map<String, ObjectTracker.ClassInfo> currentStats = objectTracker.getClassStatistics();

        // Analyze with window analyzer
        Map<String, TimeWindowAnalyzer.WindowStats> windowStats =
            windowAnalyzer.analyze(currentStats);

        for (Map.Entry<String, TimeWindowAnalyzer.WindowStats> entry : windowStats.entrySet()) {
            TimeWindowAnalyzer.WindowStats stats = entry.getValue();

            // Check for consistent growth
            if (stats.isConsistentGrowth() && stats.growthCount >= 3) {
                ObjectTracker.ClassInfo info = currentStats.get(entry.getKey());
                if (info != null && info.instanceCount >= growthThreshold) {
                    List<AllocationRecord> records = objectTracker.getObjectsByClass(info.className);

                    candidates.add(new LeakCandidate(
                        info.className,
                        info.instanceCount,
                        info.totalSize,
                        LeakType.WINDOW_BASED,
                        getTopAllocationSite(records),
                        records,
                        String.format("Consistent growth over %d windows (total growth: %d instances)",
                            stats.growthCount, stats.totalGrowth)
                    ));
                }
            }
        }

        return candidates;
    }

    /**
     * Get top allocation site from records
     */
    private String getTopAllocationSite(List<AllocationRecord> records) {
        if (records.isEmpty()) {
            return "unknown";
        }

        Map<String, Long> siteCounts = new HashMap<>();
        for (AllocationRecord record : records) {
            siteCounts.merge(record.getAllocationSite(), 1L, Long::sum);
        }

        String topSite = null;
        long maxCount = 0;

        for (Map.Entry<String, Long> entry : siteCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                topSite = entry.getKey();
            }
        }

        return topSite != null ? topSite : records.get(0).getAllocationSite();
    }

    /**
     * Create leak report from candidates
     */
    private LeakReport createReport(List<LeakCandidate> candidates) {
        // Sort by severity (size descending)
        candidates.sort(Comparator.comparingLong((LeakCandidate c) -> c.totalSize).reversed());

        return new LeakReport(
            System.currentTimeMillis(),
            candidates,
            detectionCount.get()
        );
    }

    /**
     * Add snapshot for window analysis
     *
     * @param snapshot Memory snapshot
     */
    public void addSnapshot(MemorySnapshot snapshot) {
        windowAnalyzer.addSnapshot(snapshot);
    }

    /**
     * Get all leak reports
     */
    public List<LeakReport> getLeakReports() {
        return new ArrayList<>(leakReports);
    }

    /**
     * Get latest leak report
     */
    public LeakReport getLatestReport() {
        if (leakReports.isEmpty()) {
            return null;
        }
        return leakReports.get(leakReports.size() - 1);
    }

    /**
     * Get detection count
     */
    public long getDetectionCount() {
        return detectionCount.get();
    }

    /**
     * Add leak listener
     */
    public void addListener(LeakListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove leak listener
     */
    public void removeListener(LeakListener listener) {
        listeners.remove(listener);
    }

    /**
     * Clear all data
     */
    public void clear() {
        leakReports.clear();
        windowAnalyzer.clear();
    }

    /**
     * Get age threshold
     */
    public long getAgeThreshold() {
        return ageThresholdMs;
    }

    /**
     * Get growth threshold
     */
    public int getGrowthThreshold() {
        return growthThreshold;
    }

    /**
     * Leak listener interface
     */
    public interface LeakListener {
        void onLeakDetected(LeakReport report);
    }

    /**
     * Leak type enumeration
     */
    public enum LeakType {
        AGE_BASED("Age-based detection"),
        GROWTH_BASED("Growth pattern detection"),
        WINDOW_BASED("Time window analysis"),
        REFERENCE_BASED("Reference chain analysis");

        private final String description;

        LeakType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Leak candidate holder
     */
    public static class LeakCandidate {
        public final String className;
        public final int instanceCount;
        public final long totalSize;
        public final LeakType type;
        public final String allocationSite;
        public final List<AllocationRecord> sampleRecords;
        public final String description;
        public final long detectedAt;

        public LeakCandidate(String className, int instanceCount, long totalSize,
                            LeakType type, String allocationSite,
                            List<AllocationRecord> sampleRecords, String description) {
            this.className = className;
            this.instanceCount = instanceCount;
            this.totalSize = totalSize;
            this.type = type;
            this.allocationSite = allocationSite;
            this.sampleRecords = sampleRecords.subList(0, Math.min(10, sampleRecords.size()));
            this.description = description;
            this.detectedAt = System.currentTimeMillis();
        }

        /**
         * Get severity score (0-100)
         */
        public int getSeverity() {
            int score = 0;

            // Size factor (0-40)
            if (totalSize > 1024 * 1024 * 100) score += 40; // > 100MB
            else if (totalSize > 1024 * 1024 * 10) score += 30; // > 10MB
            else if (totalSize > 1024 * 1024) score += 20; // > 1MB
            else score += 10;

            // Count factor (0-40)
            if (instanceCount > 10000) score += 40;
            else if (instanceCount > 1000) score += 30;
            else if (instanceCount > 100) score += 20;
            else score += 10;

            // Type factor (0-20)
            if (type == LeakType.WINDOW_BASED) score += 20;
            else if (type == LeakType.GROWTH_BASED) score += 15;
            else score += 10;

            return Math.min(100, score);
        }

        @Override
        public String toString() {
            return String.format("LeakCandidate{class=%s, count=%d, size=%dMB, type=%s, severity=%d}",
                className, instanceCount, totalSize / 1024 / 1024, type, getSeverity());
        }
    }
}
