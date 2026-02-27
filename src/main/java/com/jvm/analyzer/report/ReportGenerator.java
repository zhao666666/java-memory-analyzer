package com.jvm.analyzer.report;

import com.jvm.analyzer.core.*;
import com.jvm.analyzer.heap.*;
import com.jvm.analyzer.leak.*;
import com.google.gson.*;

import java.io.*;
import java.lang.management.*;
import java.text.*;
import java.util.*;

/**
 * Report Generator - Generates memory analysis reports in multiple formats
 *
 * Supports HTML, JSON, and CSV output formats.
 * Includes system information, memory statistics, and leak analysis.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class ReportGenerator {

    private final HeapAnalyzer heapAnalyzer;
    private final String generatorVersion = "1.0.0";

    /**
     * Create report generator
     *
     * @param heapAnalyzer Heap analyzer instance
     */
    public ReportGenerator(HeapAnalyzer heapAnalyzer) {
        this.heapAnalyzer = heapAnalyzer;
    }

    /**
     * Generate HTML report
     *
     * @param outputPath Output file path
     * @return true if successful
     */
    public boolean generateHtmlReport(String outputPath) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
            writer.println(generateHtmlContent());
            return true;
        } catch (IOException e) {
            System.err.println("Error generating HTML report: " + e.getMessage());
            return false;
        }
    }

    /**
     * Generate JSON report
     *
     * @param outputPath Output file path
     * @return true if successful
     */
    public boolean generateJsonReport(String outputPath) {
        try (FileWriter writer = new FileWriter(outputPath)) {
            JsonObject report = generateJsonContent();
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(report, writer);
            return true;
        } catch (IOException e) {
            System.err.println("Error generating JSON report: " + e.getMessage());
            return false;
        }
    }

    /**
     * Generate CSV report
     *
     * @param outputPath Output file path
     * @return true if successful
     */
    public boolean generateCsvReport(String outputPath) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
            generateCsvContent(writer);
            return true;
        } catch (IOException e) {
            System.err.println("Error generating CSV report: " + e.getMessage());
            return false;
        }
    }

    /**
     * Generate HTML content
     */
    private String generateHtmlContent() {
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n");
        html.append("<head>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("  <title>Java Memory Analysis Report</title>\n");
        html.append("  <style>\n");
        html.append(generateCssStyles());
        html.append("  </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("  <div class=\"container\">\n");
        html.append("    <header>\n");
        html.append("      <h1>Java Memory Analysis Report</h1>\n");
        html.append("      <p class=\"timestamp\">Generated: ").append(formatTimestamp(System.currentTimeMillis())).append("</p>\n");
        html.append("    </header>\n");

        // System Information
        html.append("    <section class=\"section\">\n");
        html.append("      <h2>System Information</h2>\n");
        html.append(generateSystemInfoTable());
        html.append("    </section>\n");

        // Memory Overview
        html.append("    <section class=\"section\">\n");
        html.append("      <h2>Memory Overview</h2>\n");
        html.append(generateMemoryOverview());
        html.append("    </section>\n");

        // Class Histogram
        html.append("    <section class=\"section\">\n");
        html.append("      <h2>Class Histogram (Top 50)</h2>\n");
        html.append(generateClassHistogramTable());
        html.append("    </section>\n");

        // Allocation Sites
        html.append("    <section class=\"section\">\n");
        html.append("      <h2>Top Allocation Sites</h2>\n");
        html.append(generateAllocationSitesTable());
        html.append("    </section>\n");

        // Leak Detection Results
        html.append("    <section class=\"section\">\n");
        html.append("      <h2>Leak Detection Results</h2>\n");
        html.append(generateLeakDetectionSection());
        html.append("    </section>\n");

        // GC Statistics
        html.append("    <section class=\"section\">\n");
        html.append("      <h2>Garbage Collection Statistics</h2>\n");
        html.append(generateGcStatistics());
        html.append("    </section>\n");

        html.append("  </div>\n");
        html.append("</body>\n");
        html.append("</html>\n");

        return html.toString();
    }

    /**
     * Generate CSS styles
     */
    private String generateCssStyles() {
        return """
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                line-height: 1.6;
                color: #333;
                max-width: 1200px;
                margin: 0 auto;
                padding: 20px;
                background: #f5f5f5;
            }
            .container {
                background: white;
                padding: 30px;
                border-radius: 8px;
                box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            }
            header {
                border-bottom: 2px solid #007bff;
                padding-bottom: 20px;
                margin-bottom: 30px;
            }
            h1 {
                color: #007bff;
                margin: 0;
            }
            h2 {
                color: #333;
                border-left: 4px solid #007bff;
                padding-left: 15px;
                margin-top: 30px;
            }
            .timestamp {
                color: #666;
                font-size: 0.9em;
            }
            .section {
                margin-bottom: 40px;
            }
            table {
                width: 100%;
                border-collapse: collapse;
                margin-top: 15px;
            }
            th, td {
                border: 1px solid #ddd;
                padding: 12px;
                text-align: left;
            }
            th {
                background: #007bff;
                color: white;
                font-weight: 600;
            }
            tr:nth-child(even) {
                background: #f8f9fa;
            }
            tr:hover {
                background: #e9ecef;
            }
            .stat-grid {
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                gap: 20px;
                margin-top: 15px;
            }
            .stat-card {
                background: #f8f9fa;
                padding: 20px;
                border-radius: 8px;
                text-align: center;
            }
            .stat-value {
                font-size: 2em;
                font-weight: bold;
                color: #007bff;
            }
            .stat-label {
                color: #666;
                font-size: 0.9em;
            }
            .leak-high { background: #f8d7da !important; }
            .leak-medium { background: #fff3cd !important; }
            .leak-low { background: #d4edda !important; }
            .progress-bar {
                background: #e9ecef;
                border-radius: 4px;
                overflow: hidden;
                height: 20px;
            }
            .progress-fill {
                height: 100%;
                background: linear-gradient(90deg, #28a745, #ffc107, #dc3545);
            }
            """;
    }

    /**
     * Generate system info table
     */
    private String generateSystemInfoTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("<table>\n");
        sb.append("  <tr><th>Property</th><th>Value</th></tr>\n");

        addSystemRow(sb, "Java Version", System.getProperty("java.version"));
        addSystemRow(sb, "Java Vendor", System.getProperty("java.vendor"));
        addSystemRow(sb, "JVM Name", System.getProperty("java.vm.name"));
        addSystemRow(sb, "JVM Version", System.getProperty("java.vm.version"));
        addSystemRow(sb, "OS Name", System.getProperty("os.name"));
        addSystemRow(sb, "OS Version", System.getProperty("os.version"));
        addSystemRow(sb, "OS Architecture", System.getProperty("os.arch"));
        addSystemRow(sb, "Available Processors", String.valueOf(Runtime.getRuntime().availableProcessors()));
        addSystemRow(sb, "Report Generator Version", generatorVersion);

        sb.append("</table>\n");
        return sb.toString();
    }

    private void addSystemRow(StringBuilder sb, String name, String value) {
        sb.append("  <tr><td>").append(name).append("</td><td>").append(value).append("</td></tr>\n");
    }

    /**
     * Generate memory overview
     */
    private String generateMemoryOverview() {
        StringBuilder sb = new StringBuilder();
        Runtime runtime = Runtime.getRuntime();

        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        sb.append("<div class=\"stat-grid\">\n");

        sb.append("  <div class=\"stat-card\">\n");
        sb.append("    <div class=\"stat-value\">").append(formatBytes(usedMemory)).append("</div>\n");
        sb.append("    <div class=\"stat-label\">Used Memory</div>\n");
        sb.append("  </div>\n");

        sb.append("  <div class=\"stat-card\">\n");
        sb.append("    <div class=\"stat-value\">").append(formatBytes(totalMemory)).append("</div>\n");
        sb.append("    <div class=\"stat-label\">Total Memory</div>\n");
        sb.append("  </div>\n");

        sb.append("  <div class=\"stat-card\">\n");
        sb.append("    <div class=\"stat-value\">").append(formatBytes(maxMemory)).append("</div>\n");
        sb.append("    <div class=\"stat-label\">Max Memory</div>\n");
        sb.append("  </div>\n");

        sb.append("  <div class=\"stat-card\">\n");
        sb.append("    <div class=\"stat-value\">").append(String.format("%.1f%%", (double) usedMemory / maxMemory * 100)).append("</div>\n");
        sb.append("    <div class=\"stat-label\">Usage</div>\n");
        sb.append("  </div>\n");

        sb.append("</div>\n");

        // Progress bar
        int usagePercent = (int) ((double) usedMemory / maxMemory * 100);
        sb.append("<div class=\"progress-bar\" style=\"margin-top: 20px;\">\n");
        sb.append("  <div class=\"progress-fill\" style=\"width: ").append(usagePercent).append("%;\"></div>\n");
        sb.append("</div>\n");

        return sb.toString();
    }

    /**
     * Generate class histogram table
     */
    private String generateClassHistogramTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("<table>\n");
        sb.append("  <tr><th>#</th><th>Class Name</th><th>Instances</th><th>Total Size</th><th>Avg Size</th></tr>\n");

        if (heapAnalyzer != null) {
            Map<String, ObjectTracker.ClassInfo> stats = heapAnalyzer.getObjectTracker().getClassStatistics();
            List<ObjectTracker.ClassInfo> sorted = new ArrayList<>(stats.values());
            sorted.sort(Comparator.comparingLong((ObjectTracker.ClassInfo c) -> c.totalSize).reversed());

            int limit = Math.min(50, sorted.size());
            for (int i = 0; i < limit; i++) {
                ObjectTracker.ClassInfo info = sorted.get(i);
                sb.append("  <tr>");
                sb.append("<td>").append(i + 1).append("</td>");
                sb.append("<td>").append(escapeHtml(info.className)).append("</td>");
                sb.append("<td>").append(formatNumber(info.instanceCount)).append("</td>");
                sb.append("<td>").append(formatBytes(info.totalSize)).append("</td>");
                sb.append("<td>").append(formatBytes(info.avgSize)).append("</td>");
                sb.append("</tr>\n");
            }
        }

        sb.append("</table>\n");
        return sb.toString();
    }

    /**
     * Generate allocation sites table
     */
    private String generateAllocationSitesTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("<table>\n");
        sb.append("  <tr><th>#</th><th>Allocation Site</th><th>Allocations</th><th>Total Size</th></tr>\n");

        if (heapAnalyzer != null) {
            Map<String, ObjectTracker.SiteInfo> stats = heapAnalyzer.getObjectTracker().getSiteStatistics();
            List<ObjectTracker.SiteInfo> sorted = new ArrayList<>(stats.values());
            sorted.sort(Comparator.comparingLong((ObjectTracker.SiteInfo s) -> s.totalSize).reversed());

            int limit = Math.min(20, sorted.size());
            for (int i = 0; i < limit; i++) {
                ObjectTracker.SiteInfo info = sorted.get(i);
                sb.append("  <tr>");
                sb.append("<td>").append(i + 1).append("</td>");
                sb.append("<td><code>").append(escapeHtml(info.site)).append("</code></td>");
                sb.append("<td>").append(formatNumber(info.allocationCount)).append("</td>");
                sb.append("<td>").append(formatBytes(info.totalSize)).append("</td>");
                sb.append("</tr>\n");
            }
        }

        sb.append("</table>\n");
        return sb.toString();
    }

    /**
     * Generate leak detection section
     */
    private String generateLeakDetectionSection() {
        StringBuilder sb = new StringBuilder();

        // Note: In a real implementation, you would get this from LeakDetector
        sb.append("<p>No leak detection results available. Run leak detection during analysis.</p>\n");

        return sb.toString();
    }

    /**
     * Generate GC statistics
     */
    private String generateGcStatistics() {
        StringBuilder sb = new StringBuilder();
        sb.append("<table>\n");
        sb.append("  <tr><th>Garbage Collector</th><th>Collections</th><th>Total Time (ms)</th><th>Avg Pause (ms)</th></tr>\n");

        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean bean : gcBeans) {
            sb.append("  <tr>");
            sb.append("<td>").append(escapeHtml(bean.getName())).append("</td>");
            sb.append("<td>").append(formatNumber(bean.getCollectionCount())).append("</td>");
            sb.append("<td>").append(formatNumber(bean.getCollectionTime())).append("</td>");
            long avgPause = bean.getCollectionCount() > 0
                ? bean.getCollectionTime() / bean.getCollectionCount()
                : 0;
            sb.append("<td>").append(formatNumber(avgPause)).append("</td>");
            sb.append("</tr>\n");
        }

        sb.append("</table>\n");
        return sb.toString();
    }

    /**
     * Generate JSON content
     */
    private JsonObject generateJsonContent() {
        JsonObject report = new JsonObject();

        report.addProperty("reportType", "Java Memory Analysis");
        report.addProperty("generatorVersion", generatorVersion);
        report.addProperty("timestamp", System.currentTimeMillis());
        report.addProperty("timestampFormatted", formatTimestamp(System.currentTimeMillis()));

        // System info
        JsonObject systemInfo = new JsonObject();
        systemInfo.addProperty("javaVersion", System.getProperty("java.version"));
        systemInfo.addProperty("javaVendor", System.getProperty("java.vendor"));
        systemInfo.addProperty("jvmName", System.getProperty("java.vm.name"));
        systemInfo.addProperty("jvmVersion", System.getProperty("java.vm.version"));
        systemInfo.addProperty("osName", System.getProperty("os.name"));
        systemInfo.addProperty("osVersion", System.getProperty("os.version"));
        systemInfo.addProperty("osArchitecture", System.getProperty("os.arch"));
        systemInfo.addProperty("availableProcessors", Runtime.getRuntime().availableProcessors());
        report.add("systemInfo", systemInfo);

        // Memory stats
        Runtime runtime = Runtime.getRuntime();
        JsonObject memoryStats = new JsonObject();
        memoryStats.addProperty("usedMemory", runtime.totalMemory() - runtime.freeMemory());
        memoryStats.addProperty("totalMemory", runtime.totalMemory());
        memoryStats.addProperty("maxMemory", runtime.maxMemory());
        memoryStats.addProperty("freeMemory", runtime.freeMemory());
        report.add("memoryStats", memoryStats);

        // Class histogram
        JsonArray classHistogram = new JsonArray();
        if (heapAnalyzer != null) {
            Map<String, ObjectTracker.ClassInfo> stats = heapAnalyzer.getObjectTracker().getClassStatistics();
            List<ObjectTracker.ClassInfo> sorted = new ArrayList<>(stats.values());
            sorted.sort(Comparator.comparingLong((ObjectTracker.ClassInfo c) -> c.totalSize).reversed());

            for (ObjectTracker.ClassInfo info : sorted) {
                JsonObject classEntry = new JsonObject();
                classEntry.addProperty("className", info.className);
                classEntry.addProperty("instanceCount", info.instanceCount);
                classEntry.addProperty("totalSize", info.totalSize);
                classEntry.addProperty("avgSize", info.avgSize);
                classHistogram.add(classEntry);
            }
        }
        report.add("classHistogram", classHistogram);

        // GC stats
        JsonArray gcStats = new JsonArray();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            JsonObject gcEntry = new JsonObject();
            gcEntry.addProperty("name", bean.getName());
            gcEntry.addProperty("collectionCount", bean.getCollectionCount());
            gcEntry.addProperty("collectionTime", bean.getCollectionTime());
            gcStats.add(gcEntry);
        }
        report.add("gcStats", gcStats);

        return report;
    }

    /**
     * Generate CSV content
     */
    private void generateCsvContent(PrintWriter writer) {
        // Section: Class Histogram
        writer.println("# Java Memory Analysis Report");
        writer.println("# Generated: " + formatTimestamp(System.currentTimeMillis()));
        writer.println();

        writer.println("# Class Histogram");
        writer.println("Rank,Class Name,Instances,Total Size (bytes),Avg Size (bytes)");

        if (heapAnalyzer != null) {
            Map<String, ObjectTracker.ClassInfo> stats = heapAnalyzer.getObjectTracker().getClassStatistics();
            List<ObjectTracker.ClassInfo> sorted = new ArrayList<>(stats.values());
            sorted.sort(Comparator.comparingLong((ObjectTracker.ClassInfo c) -> c.totalSize).reversed());

            for (int i = 0; i < sorted.size(); i++) {
                ObjectTracker.ClassInfo info = sorted.get(i);
                writer.printf("%d,%s,%d,%d,%d%n",
                    i + 1,
                    escapeCsv(info.className),
                    info.instanceCount,
                    info.totalSize,
                    info.avgSize);
            }
        }

        writer.println();

        // Section: Allocation Sites
        writer.println("# Allocation Sites");
        writer.println("Rank,Allocation Site,Allocation Count,Total Size (bytes)");

        if (heapAnalyzer != null) {
            Map<String, ObjectTracker.SiteInfo> siteStats = heapAnalyzer.getObjectTracker().getSiteStatistics();
            List<ObjectTracker.SiteInfo> sorted = new ArrayList<>(siteStats.values());
            sorted.sort(Comparator.comparingLong((ObjectTracker.SiteInfo s) -> s.totalSize).reversed());

            for (int i = 0; i < sorted.size(); i++) {
                ObjectTracker.SiteInfo info = sorted.get(i);
                writer.printf("%d,%s,%d,%d%n",
                    i + 1,
                    escapeCsv(info.site),
                    info.allocationCount,
                    info.totalSize);
            }
        }

        writer.println();

        // Section: GC Statistics
        writer.println("# Garbage Collection Statistics");
        writer.println("Collector Name,Collection Count,Total Time (ms),Avg Pause (ms)");

        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long avgPause = bean.getCollectionCount() > 0
                ? bean.getCollectionTime() / bean.getCollectionCount()
                : 0;
            writer.printf("%s,%d,%d,%d%n",
                escapeCsv(bean.getName()),
                bean.getCollectionCount(),
                bean.getCollectionTime(),
                avgPause);
        }
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    private String formatTimestamp(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private String formatNumber(long number) {
        return NumberFormat.getNumberInstance().format(number);
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    private String escapeCsv(String text) {
        if (text == null) return "";
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
