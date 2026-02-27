package com.jvm.analyzer.leak;

import com.jvm.analyzer.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Leak Report - Comprehensive memory leak report
 *
 * Contains detailed information about detected potential leaks,
 * including affected classes, allocation sites, and recommendations.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class LeakReport {

    private final long reportId;
    private final long timestamp;
    private final List<LeakDetector.LeakCandidate> candidates;
    private final long detectionNumber;

    private static final AtomicLong reportIdGenerator = new AtomicLong(0);

    /**
     * Create leak report
     *
     * @param timestamp Report timestamp
     * @param candidates Leak candidates
     * @param detectionNumber Detection sequence number
     */
    public LeakReport(long timestamp, List<LeakDetector.LeakCandidate> candidates,
                     long detectionNumber) {
        this.reportId = reportIdGenerator.incrementAndGet();
        this.timestamp = timestamp;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.detectionNumber = detectionNumber;
    }

    /**
     * Get report ID
     */
    public long getReportId() {
        return reportId;
    }

    /**
     * Get timestamp
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get leak candidates
     */
    public List<LeakDetector.LeakCandidate> getCandidates() {
        return candidates;
    }

    /**
     * Get detection number
     */
    public long getDetectionNumber() {
        return detectionNumber;
    }

    /**
     * Get candidate count
     */
    public int getCandidateCount() {
        return candidates.size();
    }

    /**
     * Get total leaked size estimate
     */
    public long getTotalLeakedSize() {
        return candidates.stream().mapToLong(c -> c.totalSize).sum();
    }

    /**
     * Get total leaked instance count
     */
    public int getTotalLeakedInstances() {
        return candidates.stream().mapToInt(c -> c.instanceCount).sum();
    }

    /**
     * Get candidates by severity
     *
     * @param minSeverity Minimum severity (0-100)
     * @return Filtered list of candidates
     */
    public List<LeakDetector.LeakCandidate> getBySeverity(int minSeverity) {
        List<LeakDetector.LeakCandidate> result = new ArrayList<>();
        for (LeakDetector.LeakCandidate candidate : candidates) {
            if (candidate.getSeverity() >= minSeverity) {
                result.add(candidate);
            }
        }
        result.sort(Comparator.comparingInt((LeakDetector.LeakCandidate c) -> c.getSeverity()).reversed());
        return result;
    }

    /**
     * Get candidates by type
     *
     * @param type Leak type
     * @return Filtered list of candidates
     */
    public List<LeakDetector.LeakCandidate> getByType(LeakDetector.LeakType type) {
        List<LeakDetector.LeakCandidate> result = new ArrayList<>();
        for (LeakDetector.LeakCandidate candidate : candidates) {
            if (candidate.type == type) {
                result.add(candidate);
            }
        }
        return result;
    }

    /**
     * Get top candidates
     *
     * @param limit Maximum number of results
     * @return Top candidates by severity
     */
    public List<LeakDetector.LeakCandidate> getTop(int limit) {
        List<LeakDetector.LeakCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingInt((LeakDetector.LeakCandidate c) -> c.getSeverity()).reversed());
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }

    /**
     * Get affected classes
     */
    public Set<String> getAffectedClasses() {
        Set<String> classes = new LinkedHashSet<>();
        for (LeakDetector.LeakCandidate candidate : candidates) {
            classes.add(candidate.className);
        }
        return classes;
    }

    /**
     * Get unique allocation sites
     */
    public Set<String> getAllocationSites() {
        Set<String> sites = new LinkedHashSet<>();
        for (LeakDetector.LeakCandidate candidate : candidates) {
            sites.add(candidate.allocationSite);
        }
        return sites;
    }

    /**
     * Get summary
     */
    public Summary getSummary() {
        int highSeverity = 0;
        int mediumSeverity = 0;
        int lowSeverity = 0;

        for (LeakDetector.LeakCandidate candidate : candidates) {
            int severity = candidate.getSeverity();
            if (severity >= 70) {
                highSeverity++;
            } else if (severity >= 40) {
                mediumSeverity++;
            } else {
                lowSeverity++;
            }
        }

        return new Summary(
            candidates.size(),
            highSeverity,
            mediumSeverity,
            lowSeverity,
            getTotalLeakedSize(),
            getTotalLeakedInstances()
        );
    }

    /**
     * Generate recommendations
     */
    public List<String> getRecommendations() {
        List<String> recommendations = new ArrayList<>();

        if (candidates.isEmpty()) {
            recommendations.add("No potential leaks detected. Continue monitoring.");
            return recommendations;
        }

        Summary summary = getSummary();

        if (summary.highSeverity > 0) {
            recommendations.add(String.format(
                "URGENT: %d high-severity potential leaks detected. Immediate investigation recommended.",
                summary.highSeverity));
        }

        // Group by type
        Map<LeakDetector.LeakType, Long> byType = new EnumMap<>(LeakDetector.LeakType.class);
        for (LeakDetector.LeakCandidate c : candidates) {
            byType.merge(c.type, 1L, Long::sum);
        }

        if (byType.getOrDefault(LeakDetector.LeakType.AGE_BASED, 0L) > 0) {
            recommendations.add("Age-based detection found long-lived objects. " +
                "Check for static collections, caches without eviction, or unclosed resources.");
        }

        if (byType.getOrDefault(LeakDetector.LeakType.GROWTH_BASED, 0L) > 0) {
            recommendations.add("Growth pattern detected. " +
                "Look for unbounded collections, missing cleanup in loops, or event listener accumulation.");
        }

        if (byType.getOrDefault(LeakDetector.LeakType.WINDOW_BASED, 0L) > 0) {
            recommendations.add("Time window analysis shows consistent growth. " +
                "This strongly indicates a memory leak. Review recent code changes.");
        }

        // Top leak recommendations
        if (!candidates.isEmpty()) {
            LeakDetector.LeakCandidate top = candidates.get(0);
            recommendations.add(String.format(
                "Top suspect: %s with %d instances (%.2f MB) at %s",
                top.className, top.instanceCount, top.totalSize / 1024.0 / 1024.0,
                top.allocationSite));
        }

        return recommendations;
    }

    @Override
    public String toString() {
        Summary summary = getSummary();
        return String.format(
            "LeakReport{id=%d, candidates=%d, high=%d, medium=%d, low=%d, totalSize=%dMB}",
            reportId, summary.totalCandidates, summary.highSeverity,
            summary.mediumSeverity, summary.lowSeverity,
            summary.totalSize / 1024 / 1024);
    }

    /**
     * Report summary
     */
    public static class Summary {
        public final int totalCandidates;
        public final int highSeverity;
        public final int mediumSeverity;
        public final int lowSeverity;
        public final long totalSize;
        public final int totalInstances;

        public Summary(int totalCandidates, int highSeverity, int mediumSeverity,
                      int lowSeverity, long totalSize, int totalInstances) {
            this.totalCandidates = totalCandidates;
            this.highSeverity = highSeverity;
            this.mediumSeverity = mediumSeverity;
            this.lowSeverity = lowSeverity;
            this.totalSize = totalSize;
            this.totalInstances = totalInstances;
        }

        @Override
        public String toString() {
            return String.format(
                "Summary{total=%d, high=%d, medium=%d, low=%d, size=%dMB, instances=%d}",
                totalCandidates, highSeverity, mediumSeverity, lowSeverity,
                totalSize / 1024 / 1024, totalInstances);
        }
    }
}
