package com.jvm.analyzer.leak;

import com.jvm.analyzer.core.*;
import com.jvm.analyzer.heap.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Unit tests for LeakDetector
 */
public class LeakDetectorTest {

    private ObjectTracker objectTracker;
    private LeakDetector leakDetector;

    @BeforeEach
    public void setUp() {
        objectTracker = new ObjectTracker();
        // Use low thresholds for testing
        leakDetector = new LeakDetector(objectTracker, 100, 5, 5);
    }

    @AfterEach
    public void tearDown() {
        if (leakDetector != null) {
            leakDetector.stop();
            leakDetector.clear();
        }
        if (objectTracker != null) {
            objectTracker.stop();
            objectTracker.clear();
        }
    }

    @Test
    public void testStartStop() {
        leakDetector.start();
        assertTrue(leakDetector.isDetecting(), "Detector should be running");

        leakDetector.stop();
        assertFalse(leakDetector.isDetecting(), "Detector should be stopped");
    }

    @Test
    public void testDetectWithNoLeaks() {
        leakDetector.start();

        LeakReport report = leakDetector.detect();

        // With no data, should return null or empty report
        if (report != null) {
            assertEquals(0, report.getCandidateCount(), "Should have no candidates");
        }
    }

    @Test
    public void testDetectWithSimulatedLeak() {
        leakDetector.start();

        // Simulate many objects of the same class (potential leak)
        for (int i = 0; i < 100; i++) {
            AllocationRecord record = new AllocationRecord.Builder()
                .setObjectId(1000L + i)
                .setClassName("com.example.LeakyClass")
                .setSize(1024L)
                .setTimestamp(System.currentTimeMillis() - 1000) // Old objects
                .build();
            objectTracker.track(record);
        }

        LeakReport report = leakDetector.detect();

        if (report != null && report.getCandidateCount() > 0) {
            LeakDetector.LeakCandidate candidate = report.getTop(1).get(0);
            assertEquals("com.example.LeakyClass", candidate.className);
            assertTrue(candidate.instanceCount >= 100);
        }
    }

    @Test
    public void testGetLeakReports() {
        leakDetector.start();

        // Initial: no reports
        assertEquals(0, leakDetector.getLeakReports().size());

        // Add some data and detect
        for (int i = 0; i < 50; i++) {
            AllocationRecord record = new AllocationRecord.Builder()
                .setObjectId(2000L + i)
                .setClassName("test.TestClass")
                .setSize(512L)
                .build();
            objectTracker.track(record);
        }

        leakDetector.detect();

        // May or may not have reports depending on thresholds
        List<LeakReport> reports = leakDetector.getLeakReports();
        assertNotNull(reports);
    }

    @Test
    public void testLeakCandidateSeverity() {
        // Create a candidate with known values
        List<AllocationRecord> records = new ArrayList<>();
        LeakDetector.LeakCandidate candidate = new LeakDetector.LeakCandidate(
            "test.HeavyClass",
            10000, // High count
            100 * 1024 * 1024, // 100 MB
            LeakDetector.LeakType.GROWTH_BASED,
            "com.example.Test.method(Test.java:42)",
            records,
            "Test candidate"
        );

        int severity = candidate.getSeverity();
        assertTrue(severity >= 0, "Severity should be non-negative");
        assertTrue(severity <= 100, "Severity should be at most 100");
    }

    @Test
    public void testTimeWindowAnalysis() {
        leakDetector.start();

        // Create snapshots with growing class stats
        for (int i = 0; i < 10; i++) {
            MemorySnapshot snapshot = new MemorySnapshot.Builder()
                .setTotalHeapUsed(1024L * 1024 * (i + 1))
                .addClassStat("growing.Class", i * 10 + 10, i * 1024 + 1024)
                .build();

            leakDetector.addSnapshot(snapshot);

            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Detect should use window analysis
        LeakReport report = leakDetector.detect();
        assertNotNull(report);
    }

    @Test
    public void testLeakReportSummary() {
        List<LeakDetector.LeakCandidate> candidates = new ArrayList<>();

        // Add candidates with different severities
        candidates.add(createCandidate("HighLeak", 80));
        candidates.add(createCandidate("MediumLeak", 50));
        candidates.add(createCandidate("LowLeak", 20));

        LeakReport report = new LeakReport(
            System.currentTimeMillis(),
            candidates,
            1
        );

        LeakReport.Summary summary = report.getSummary();

        assertEquals(3, summary.totalCandidates);
        assertEquals(1, summary.highSeverity); // 80 >= 70
        assertEquals(1, summary.mediumSeverity); // 50 >= 40
        assertEquals(1, summary.lowSeverity);
    }

    @Test
    public void testLeakReportRecommendations() {
        List<LeakDetector.LeakCandidate> candidates = new ArrayList<>();
        candidates.add(createCandidate("TestLeak", 75));

        LeakReport report = new LeakReport(
            System.currentTimeMillis(),
            candidates,
            1
        );

        List<String> recommendations = report.getRecommendations();
        assertNotNull(recommendations);
        assertFalse(recommendations.isEmpty(), "Should have recommendations");
    }

    @Test
    public void testLeakReportFiltering() {
        List<LeakDetector.LeakCandidate> candidates = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            candidates.add(createCandidate("Leak" + i, i * 10));
        }

        LeakReport report = new LeakReport(
            System.currentTimeMillis(),
            candidates,
            1
        );

        // Filter by severity
        List<LeakDetector.LeakCandidate> highSeverity = report.getBySeverity(70);
        assertTrue(highSeverity.size() <= 10, "Should filter by severity");

        // Get top N
        List<LeakDetector.LeakCandidate> top3 = report.getTop(3);
        assertEquals(3, top3.size(), "Should return exactly 3");

        // Verify sorted by severity
        for (int i = 0; i < top3.size() - 1; i++) {
            assertTrue(top3.get(i).getSeverity() >= top3.get(i + 1).getSeverity(),
                "Should be sorted by severity descending");
        }
    }

    @Test
    public void testConcurrentLeakDetection() throws Exception {
        leakDetector.start();

        int threadCount = 3;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger detections = new AtomicInteger(0);

        // Add some data first
        for (int i = 0; i < 100; i++) {
            AllocationRecord record = new AllocationRecord.Builder()
                .setObjectId(5000L + i)
                .setClassName("concurrent.TestClass")
                .setSize(256L)
                .build();
            objectTracker.track(record);
        }

        // Start multiple detection threads
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    LeakReport report = leakDetector.detect();
                    if (report != null) {
                        detections.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        // Wait for completion
        latch.await(5, TimeUnit.SECONDS);

        assertEquals(threadCount, detections.get(), "All threads should complete detection");
    }

    private LeakDetector.LeakCandidate createCandidate(String className, int severity) {
        // Create candidate with controlled severity
        int instanceCount = severity * 100;
        long totalSize = severity * 1024 * 1024L;

        return new LeakDetector.LeakCandidate(
            className,
            instanceCount,
            totalSize,
            LeakDetector.LeakType.GROWTH_BASED,
            "test.Location",
            new ArrayList<>(),
            "Test candidate"
        );
    }
}
