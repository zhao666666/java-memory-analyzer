package com.jvm.analyzer.util;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * System Information Utility
 *
 * Collects and provides system and JVM information
 * for inclusion in reports and diagnostics.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class SystemInfo {

    private static final Map<String, String> cachedInfo = new ConcurrentHashMap<>();

    /**
     * Get all system information
     */
    public static Map<String, String> getAll() {
        Map<String, String> info = new LinkedHashMap<>();

        // JVM Information
        info.put("java.version", System.getProperty("java.version"));
        info.put("java.vendor", System.getProperty("java.vendor"));
        info.put("java.home", System.getProperty("java.home"));
        info.put("java.vm.name", System.getProperty("java.vm.name"));
        info.put("java.vm.version", System.getProperty("java.vm.version"));
        info.put("java.vm.vendor", System.getProperty("java.vm.vendor"));
        info.put("java.vm.specification.version", System.getProperty("java.vm.specification.version"));
        info.put("java.vm.specification.vendor", System.getProperty("java.vm.specification.vendor"));

        // OS Information
        info.put("os.name", System.getProperty("os.name"));
        info.put("os.arch", System.getProperty("os.arch"));
        info.put("os.version", System.getProperty("os.version"));

        // CPU Information
        info.put("available.processors", String.valueOf(Runtime.getRuntime().availableProcessors()));

        // Memory Information
        Runtime runtime = Runtime.getRuntime();
        info.put("max.memory", String.valueOf(runtime.maxMemory()));
        info.put("total.memory", String.valueOf(runtime.totalMemory()));
        info.put("free.memory", String.valueOf(runtime.freeMemory()));

        // Environment
        info.put("user.dir", System.getProperty("user.dir"));
        info.put("user.home", System.getProperty("user.home"));
        info.put("user.timezone", System.getProperty("user.timezone"));
        info.put("file.encoding", System.getProperty("file.encoding"));

        // Uptime
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        info.put("jvm.uptime.ms", String.valueOf(runtimeMXBean.getUptime()));
        info.put("jvm.start.time", String.valueOf(runtimeMXBean.getStartTime()));

        // Thread count
        info.put("thread.count", String.valueOf(Thread.activeCount()));

        // Class loading
        ClassLoadingMXBean classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        info.put("loaded.class.count", String.valueOf(classLoadingMXBean.getLoadedClassCount()));
        info.put("total.loaded.class.count", String.valueOf(classLoadingMXBean.getTotalLoadedClassCount()));
        info.put("unloaded.class.count", String.valueOf(classLoadingMXBean.getUnloadedClassCount()));

        // File descriptors (if available)
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean =
                    (com.sun.management.OperatingSystemMXBean) osBean;
                info.put("process.cpu.load", String.valueOf(sunOsBean.getProcessCpuLoad()));
                info.put("system.cpu.load", String.valueOf(sunOsBean.getSystemCpuLoad()));
                info.put("system.load.average", String.valueOf(osBean.getSystemLoadAverage()));
            }
        } catch (Exception e) {
            // OS-specific beans not available
        }

        return info;
    }

    /**
     * Get formatted system info string
     */
    public static String getFormattedInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("System Information\n");
        sb.append("==================\n\n");

        Map<String, String> info = getAll();
        int maxKeyLength = info.keySet().stream().mapToInt(String::length).max().orElse(20);

        for (Map.Entry<String, String> entry : info.entrySet()) {
            sb.append(String.format("%-" + maxKeyLength + "s : %s\n", entry.getKey(), entry.getValue()));
        }

        return sb.toString();
    }

    /**
     * Get JVM uptime in seconds
     */
    public static long getUptimeSeconds() {
        return ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
    }

    /**
     * Get JVM uptime formatted string
     */
    public static String getFormattedUptime() {
        long uptime = getUptimeSeconds();
        long hours = uptime / 3600;
        long minutes = (uptime % 3600) / 60;
        long seconds = uptime % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * Get memory info formatted string
     */
    public static String getMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long used = total - free;

        return String.format(
            "Used: %,d MB / Max: %,d MB (%.1f%%) | Free: %,d MB",
            used / 1024 / 1024,
            max / 1024 / 1024,
            (double) used / max * 100,
            free / 1024 / 1024
        );
    }
}
