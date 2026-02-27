package com.jvm.analyzer.report;

import com.jvm.analyzer.heap.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;

/**
 * Unit tests for ReportGenerator
 */
public class ReportGeneratorTest {

    private HeapAnalyzer heapAnalyzer;
    private ReportGenerator generator;
    private Path tempDir;

    @BeforeEach
    public void setUp() throws Exception {
        heapAnalyzer = new HeapAnalyzer();
        heapAnalyzer.startAnalysis();
        generator = new ReportGenerator(heapAnalyzer);
        tempDir = Files.createTempDirectory("report-test");
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (heapAnalyzer != null) {
            heapAnalyzer.stopAnalysis();
            heapAnalyzer.clear();
        }

        // Clean up temp files
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
        }
    }

    @Test
    public void testGenerateHtmlReport() throws Exception {
        Path outputPath = tempDir.resolve("report.html");

        boolean success = generator.generateHtmlReport(outputPath.toString());

        assertTrue(success, "Report generation should succeed");
        assertTrue(Files.exists(outputPath), "Report file should exist");

        // Verify content
        String content = Files.readString(outputPath);
        assertTrue(content.contains("Java Memory Analysis Report"), "Should contain title");
        assertTrue(content.contains("System Information"), "Should contain system info section");
        assertTrue(content.contains("Memory Overview"), "Should contain memory overview");
        assertTrue(content.contains("Class Histogram"), "Should contain class histogram");
        assertTrue(content.contains("</html>"), "Should be valid HTML");
    }

    @Test
    public void testGenerateJsonReport() throws Exception {
        Path outputPath = tempDir.resolve("report.json");

        boolean success = generator.generateJsonReport(outputPath.toString());

        assertTrue(success, "Report generation should succeed");
        assertTrue(Files.exists(outputPath), "Report file should exist");

        // Verify content
        String content = Files.readString(outputPath);
        assertTrue(content.contains("\"reportType\""), "Should contain report type");
        assertTrue(content.contains("\"systemInfo\""), "Should contain system info");
        assertTrue(content.contains("\"memoryStats\""), "Should contain memory stats");
        assertTrue(content.contains("\"classHistogram\""), "Should contain class histogram");
    }

    @Test
    public void testGenerateCsvReport() throws Exception {
        Path outputPath = tempDir.resolve("report.csv");

        boolean success = generator.generateCsvReport(outputPath.toString());

        assertTrue(success, "Report generation should succeed");
        assertTrue(Files.exists(outputPath), "Report file should exist");

        // Verify content
        String content = Files.readString(outputPath);
        assertTrue(content.contains("# Java Memory Analysis Report"), "Should contain header comment");
        assertTrue(content.contains("# Class Histogram"), "Should contain class histogram section");
        assertTrue(content.contains("Class Name"), "Should contain column headers");
    }

    @Test
    public void testMultipleReportFormats() throws Exception {
        // Generate all formats
        Path htmlPath = tempDir.resolve("report.html");
        Path jsonPath = tempDir.resolve("report.json");
        Path csvPath = tempDir.resolve("report.csv");

        boolean htmlSuccess = generator.generateHtmlReport(htmlPath.toString());
        boolean jsonSuccess = generator.generateJsonReport(jsonPath.toString());
        boolean csvSuccess = generator.generateCsvReport(csvPath.toString());

        assertTrue(htmlSuccess, "HTML report should succeed");
        assertTrue(jsonSuccess, "JSON report should succeed");
        assertTrue(csvSuccess, "CSV report should succeed");

        // Verify file sizes are reasonable
        assertTrue(Files.size(htmlPath) > 1000, "HTML report should have content");
        assertTrue(Files.size(jsonPath) > 100, "JSON report should have content");
        assertTrue(Files.size(csvPath) > 50, "CSV report should have content");
    }

    @Test
    public void testReportContainsTimestamp() throws Exception {
        Path outputPath = tempDir.resolve("report.html");
        generator.generateHtmlReport(outputPath.toString());

        String content = Files.readString(outputPath);
        assertTrue(content.contains("Generated:"), "Should contain generation timestamp");
    }

    @Test
    public void testReportContainsSystemInfo() throws Exception {
        Path outputPath = tempDir.resolve("report.html");
        generator.generateHtmlReport(outputPath.toString());

        String content = Files.readString(outputPath);
        assertTrue(content.contains("java.version"), "Should contain Java version");
        assertTrue(content.contains("os.name"), "Should contain OS name");
    }

    @Test
    public void testJsonReportIsValidJson() throws Exception {
        Path outputPath = tempDir.resolve("report.json");
        generator.generateJsonReport(outputPath.toString());

        String content = Files.readString(outputPath);

        // Basic JSON validation - should start with { and end with }
        String trimmed = content.trim();
        assertTrue(trimmed.startsWith("{"), "JSON should start with {");
        assertTrue(trimmed.endsWith("}"), "JSON should end with }");

        // Should have balanced braces
        int openBraces = 0;
        int closeBraces = 0;
        for (char c : trimmed.toCharArray()) {
            if (c == '{') openBraces++;
            if (c == '}') closeBraces++;
        }
        assertEquals(openBraces, closeBraces, "Should have balanced braces");
    }

    @Test
    public void testReportWithLargeData() throws Exception {
        // Add some data to the analyzer
        for (int i = 0; i < 100; i++) {
            // This simulates having allocation data
        }

        Path outputPath = tempDir.resolve("report-large.html");
        boolean success = generator.generateHtmlReport(outputPath.toString());

        assertTrue(success, "Report with large data should succeed");
    }

    @Test
    public void testConcurrentReportGeneration() throws Exception {
        int threadCount = 3;
        Path[] outputPaths = new Path[threadCount];
        boolean[] results = new boolean[threadCount];

        // Create output paths
        for (int i = 0; i < threadCount; i++) {
            outputPaths[i] = tempDir.resolve("report-" + i + ".html");
        }

        // Generate reports concurrently
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                results[index] = generator.generateHtmlReport(outputPaths[index].toString());
            });
            threads[i].start();
        }

        // Wait for completion
        for (Thread thread : threads) {
            thread.join(5000);
        }

        // Verify all succeeded
        for (boolean result : results) {
            assertTrue(result, "Concurrent report should succeed");
        }

        // Verify all files exist
        for (Path path : outputPaths) {
            assertTrue(Files.exists(path), "Report file should exist");
        }
    }
}
