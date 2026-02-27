package com.jvm.analyzer.cli;

import com.jvm.analyzer.attach.*;
import com.jvm.analyzer.core.*;
import com.jvm.analyzer.heap.*;
import com.jvm.analyzer.leak.*;
import com.jvm.analyzer.report.*;

import java.io.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Memory Analyzer CLI - Command Line Interface
 *
 * Provides interactive and batch mode operations for memory analysis.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class MemoryAnalyzerCli {

    private HeapAnalyzer heapAnalyzer;
    private LeakDetector leakDetector;
    private ProcessAttacher attacher;

    private volatile int targetPid = -1;
    private volatile boolean attached = false;
    private volatile boolean analyzing = false;
    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService executor;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Scanner scanner = new Scanner(System.in);

    // Commands
    private final Map<String, Command> commands = new HashMap<>();

    /**
     * Command interface
     */
    interface Command {
        String getName();
        String getDescription();
        String getUsage();
        void execute(String[] args);
    }

    /**
     * Run CLI with arguments
     */
    public void run(String[] args) {
        initializeCommands();

        // Parse initial arguments
        if (args.length > 0) {
            executeBatch(args);
        } else {
            // Interactive mode
            showBanner();
            showHelp();
            interactiveLoop();
        }
    }

    /**
     * Initialize commands
     */
    private void initializeCommands() {
        commands.put("help", new HelpCommand());
        commands.put("attach", new AttachCommand());
        commands.put("detach", new DetachCommand());
        commands.put("status", new StatusCommand());
        commands.put("processes", new ProcessesCommand());
        commands.put("start", new StartCommand());
        commands.put("stop", new StopCommand());
        commands.put("snapshot", new SnapshotCommand());
        commands.put("leaks", new LeaksCommand());
        commands.put("histogram", new HistogramCommand());
        commands.put("report", new ReportCommand());
        commands.put("gc", new GcCommand());
        commands.put("watch", new WatchCommand());
        commands.put("debug", new DebugCommand());
        commands.put("exit", new ExitCommand());
        commands.put("quit", new ExitCommand());
    }

    /**
     * Execute batch mode
     */
    private void executeBatch(String[] args) {
        List<String> commandArgs = new ArrayList<>(Arrays.asList(args));

        // Process commands
        while (!commandArgs.isEmpty()) {
            String cmd = commandArgs.remove(0);
            List<String> cmdSpecificArgs = new ArrayList<>();

            // Collect arguments for this command
            while (!commandArgs.isEmpty() && !commandArgs.get(0).startsWith("--")) {
                cmdSpecificArgs.add(commandArgs.remove(0));
            }

            Command command = commands.get(cmd.toLowerCase());
            if (command != null) {
                command.execute(cmdSpecificArgs.toArray(new String[0]));
            } else {
                System.err.println("未知命令：" + cmd);
                System.err.println("使用 'help' 查看可用命令。");
            }
        }
    }

    /**
     * Interactive loop
     */
    private void interactiveLoop() {
        executor = Executors.newCachedThreadPool();

        while (running.get()) {
            try {
                System.out.print("\n> ");
                System.out.flush();

                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                // Parse command and arguments
                String[] parts = line.split("\\s+", 2);
                String cmd = parts[0].toLowerCase();
                String[] args = parts.length > 1 ? parts[1].split("\\s+") : new String[0];

                Command command = commands.get(cmd);
                if (command != null) {
                    command.execute(args);
                } else {
                    System.err.println("未知命令：" + cmd);
                    System.err.println("输入 'help' 查看可用命令。");
                }

            } catch (NoSuchElementException e) {
                // EOF
                break;
            } catch (Exception e) {
                System.err.println("错误：" + e.getMessage());
            }
        }

        cleanup();
    }

    /**
     * Show banner
     */
    private void showBanner() {
        System.out.println();
        System.out.println("  ____             _   _                      _ ");
        System.out.println(" |  _ \\ ___  _ __ | |_(_) ___  _ __   ___  ___| |_ ");
        System.out.println(" | |_) / _ \\| '_ \\| __| |/ _ \\| '_ \\ / _ \\/ __| __|");
        System.out.println(" |  __/ (_) | | | | |_| | (_) | | | |  __/ (__| |_ ");
        System.out.println(" |_|   \\___/|_| |_|\\__|_|\\___/|_| |_|\\___|\\___|\\__|");
        System.out.println();
        System.out.println(" Java 内存分析工具 v1.0.0");
        System.out.println(" 输入 'help' 查看可用命令。");
        System.out.println();
    }

    /**
     * Show help
     */
    private void showHelp() {
        System.out.println("可用命令:");
        System.out.println();
        System.out.printf("  %-15s %s%n", "attach <pid>", "附着到 Java 进程");
        System.out.printf("  %-15s %s%n", "detach", "从当前进程分离");
        System.out.printf("  %-15s %s%n", "processes", "列出 Java 进程");
        System.out.printf("  %-15s %s%n", "status", "显示当前状态");
        System.out.printf("  %-15s %s%n", "start", "开始内存分析");
        System.out.printf("  %-15s %s%n", "stop", "停止内存分析");
        System.out.printf("  %-15s %s%n", "snapshot", "获取内存快照");
        System.out.printf("  %-15s %s%n", "histogram", "显示类直方图");
        System.out.printf("  %-15s %s%n", "leaks", "检测内存泄漏");
        System.out.printf("  %-15s %s%n", "gc", "显示 GC 统计");
        System.out.printf("  %-15s %s%n", "watch", "实时监控内存");
        System.out.printf("  %-15s %s%n", "report", "生成报告");
        System.out.printf("  %-15s %s%n", "debug", "调试模式（生成测试数据）");
        System.out.printf("  %-15s %s%n", "help", "显示帮助");
        System.out.printf("  %-15s %s%n", "exit/quit", "退出程序");
        System.out.println();
    }

    /**
     * Cleanup resources
     */
    private void cleanup() {
        if (attached) {
            detach();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Attach to process
     */
    private void attach(int pid) {
        attach(String.valueOf(pid));
    }

    private void attach(String pidStr) {
        try {
            int pid = Integer.parseInt(pidStr);
            System.out.println("正在附着到进程 " + pid + "...");

            attacher = new ProcessAttacher();
            ProcessAttacher.AttachmentResult result = attacher.attach(pid);

            if (result.success) {
                targetPid = pid;
                attached = true;
                heapAnalyzer = new HeapAnalyzer();
                System.out.println("成功附着到进程 " + pid);
            } else {
                System.err.println("附着失败：" + result.message);
            }

        } catch (NumberFormatException e) {
            System.err.println("无效的 PID: " + pidStr);
        } catch (Exception e) {
            System.err.println("附着时出错：" + e.getMessage());
        }
    }

    /**
     * Detach from process
     */
    private void detach() {
        if (!attached) return;

        if (analyzing) {
            stopAnalysis();
        }

        attached = false;
        targetPid = -1;
        heapAnalyzer = null;
        leakDetector = null;

        System.out.println("已从进程分离。");
    }

    /**
     * Start analysis
     */
    private void startAnalysis() {
        if (!attached) {
            System.err.println("未附着到任何进程。请先使用 'attach <pid>'。");
            return;
        }

        if (analyzing) {
            System.err.println("分析已在运行中。");
            return;
        }

        heapAnalyzer.startAnalysis();
        leakDetector = new LeakDetector(heapAnalyzer.getObjectTracker());
        leakDetector.start();
        analyzing = true;

        System.out.println("内存分析已启动。");
    }

    /**
     * Stop analysis
     */
    private void stopAnalysis() {
        if (!analyzing) {
            return;
        }

        heapAnalyzer.stopAnalysis();
        if (leakDetector != null) {
            leakDetector.stop();
        }
        analyzing = false;

        System.out.println("内存分析已停止。");
    }

    // ========================================================================
    // Command Implementations
    // ========================================================================

    private class HelpCommand implements Command {
        public String getName() { return "help"; }
        public String getDescription() { return "显示帮助"; }
        public String getUsage() { return "help"; }
        public void execute(String[] args) { showHelp(); }
    }

    private class AttachCommand implements Command {
        public String getName() { return "attach"; }
        public String getDescription() { return "附着到 Java 进程"; }
        public String getUsage() { return "attach <pid>"; }
        public void execute(String[] args) {
            if (args.length < 1) {
                System.err.println("用法：attach <pid>");
                return;
            }
            attach(args[0]);
        }
    }

    private class DetachCommand implements Command {
        public String getName() { return "detach"; }
        public String getDescription() { return "从当前进程分离"; }
        public String getUsage() { return "detach"; }
        public void execute(String[] args) { detach(); }
    }

    private class StatusCommand implements Command {
        public String getName() { return "status"; }
        public String getDescription() { return "显示当前状态"; }
        public String getUsage() { return "status"; }
        public void execute(String[] args) {
            System.out.println("目标 PID: " + (attached ? targetPid : "无"));
            System.out.println("已附着：" + attached);
            System.out.println("分析中：" + analyzing);

            if (attached && heapAnalyzer != null) {
                Runtime runtime = Runtime.getRuntime();
                long used = runtime.totalMemory() - runtime.freeMemory();
                System.out.println("堆已用：" + (used / 1024 / 1024) + " MB");
                System.out.println("跟踪对象数：" + heapAnalyzer.getObjectTracker().getTrackedCount());
            }
        }
    }

    private class ProcessesCommand implements Command {
        public String getName() { return "processes"; }
        public String getDescription() { return "列出 Java 进程"; }
        public String getUsage() { return "processes"; }
        public void execute(String[] args) {
            List<ProcessAttacher.ProcessInfo> processes = ProcessAttacher.listJavaProcesses();

            if (processes.isEmpty()) {
                System.out.println("未找到 Java 进程。");
                return;
            }

            System.out.printf("%-10s %-30s %s%n", "PID", "显示名称", "主类");
            System.out.println(new String(new char[80]).replace('\0', '-'));

            for (ProcessAttacher.ProcessInfo p : processes) {
                String mainClass = p.mainClass.length() > 50
                    ? p.mainClass.substring(0, 47) + "..."
                    : p.mainClass;
                System.out.printf("%-10s %-30s %s%n", p.pid, p.displayName, mainClass);
            }
        }
    }

    private class StartCommand implements Command {
        public String getName() { return "start"; }
        public String getDescription() { return "开始内存分析"; }
        public String getUsage() { return "start"; }
        public void execute(String[] args) { startAnalysis(); }
    }

    private class StopCommand implements Command {
        public String getName() { return "stop"; }
        public String getDescription() { return "停止内存分析"; }
        public String getUsage() { return "stop"; }
        public void execute(String[] args) { stopAnalysis(); }
    }

    private class SnapshotCommand implements Command {
        public String getName() { return "snapshot"; }
        public String getDescription() { return "获取内存快照"; }
        public String getUsage() { return "snapshot"; }
        public void execute(String[] args) {
            if (!attached || heapAnalyzer == null) {
                System.err.println("未附着到任何进程。");
                return;
            }

            MemorySnapshot snapshot = heapAnalyzer.takeSnapshot();
            System.out.println("已获取快照:");
            System.out.println("  编号：" + snapshot.getSnapshotId());
            System.out.println("  堆已用：" + (snapshot.getTotalHeapUsed() / 1024 / 1024) + " MB");
            System.out.println("  类数量：" + snapshot.getClassStats().size());
            System.out.println("  分配数：" + snapshot.getAllocations().size());
        }
    }

    private class HistogramCommand implements Command {
        public String getName() { return "histogram"; }
        public String getDescription() { return "显示类直方图"; }
        public String getUsage() { return "histogram [limit]"; }
        public void execute(String[] args) {
            int limit = 20;
            if (args.length > 0) {
                try {
                    limit = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    // Use default
                }
            }

            // Try to get stats from InstrumentedAllocationRecorder first
            try {
                Class<?> recorderClass = Class.forName("com.jvm.analyzer.heap.InstrumentedAllocationRecorder");
                Object instance = recorderClass.getMethod("getInstance").invoke(null);
                Object statsObj = recorderClass.getMethod("getClassStats").invoke(instance);

                if (statsObj instanceof java.util.Map) {
                    Map<String, Object> stats = (Map<String, Object>) statsObj;
                    if (!stats.isEmpty()) {
                        printInstrumentedHistogram(stats, limit);
                        return;
                    }
                }
            } catch (Exception e) {
                // Fall through to original implementation
            }

            // Original implementation for local HeapAnalyzer
            if (!attached || heapAnalyzer == null) {
                System.err.println("未附着到任何进程。请使用 'attach <pid>' 或先用 javaagent 启动。");
                return;
            }

            Map<String, ObjectTracker.ClassInfo> stats =
                heapAnalyzer.getObjectTracker().getClassStatistics();

            List<ObjectTracker.ClassInfo> sorted = new ArrayList<>(stats.values());
            sorted.sort(Comparator.comparingLong((ObjectTracker.ClassInfo c) -> c.totalSize).reversed());

            System.out.printf("%-6s %-50s %15s %15s %15s%n",
                "#", "类名", "实例数", "总大小", "平均大小");
            System.out.println(new String(new char[110]).replace('\0', '-'));

            int count = Math.min(limit, sorted.size());
            for (int i = 0; i < count; i++) {
                ObjectTracker.ClassInfo info = sorted.get(i);
                String className = info.className.length() > 48
                    ? "..." + info.className.substring(info.className.length() - 45)
                    : info.className;
                System.out.printf("%-6d %-50s %15d %15d %15d%n",
                    i + 1, className, info.instanceCount, info.totalSize, info.avgSize);
            }
        }

        @SuppressWarnings("unchecked")
        private void printInstrumentedHistogram(Map<String, Object> stats, int limit) {
            List sorted = new ArrayList<>(stats.entrySet());
            sorted.sort((a, b) -> {
                try {
                    java.util.Map.Entry aa = (java.util.Map.Entry) a;
                    java.util.Map.Entry bb = (java.util.Map.Entry) b;
                    Object statsObjA = aa.getValue();
                    Object statsObjB = bb.getValue();
                    long sizeA = (Long) statsObjA.getClass().getField("totalSize").get(statsObjA);
                    long sizeB = (Long) statsObjB.getClass().getField("totalSize").get(statsObjB);
                    return Long.compare(sizeB, sizeA); // Descending order
                } catch (Exception ex) {
                    return 0;
                }
            });

            System.out.printf("%-6s %-50s %15s %15s %15s%n",
                "#", "类名", "实例数", "总大小", "平均大小");
            System.out.println(new String(new char[110]).replace('\0', '-'));

            int count = Math.min(limit, sorted.size());
            for (int i = 0; i < count; i++) {
                try {
                    java.util.Map.Entry entry = (java.util.Map.Entry) sorted.get(i);
                    Object statsObj = entry.getValue();
                    Long instanceCountObj = (Long) statsObj.getClass().getField("instanceCount").get(statsObj);
                    Long totalSizeObj = (Long) statsObj.getClass().getField("totalSize").get(statsObj);
                    long instanceCount = instanceCountObj != null ? instanceCountObj : 0L;
                    long totalSize = totalSizeObj != null ? totalSizeObj : 0L;
                    long avgSize = instanceCount > 0 ? totalSize / instanceCount : 0;

                    String className = (String) entry.getKey();
                    String displayClassName = className.length() > 48
                        ? "..." + className.substring(className.length() - 45)
                        : className;
                    System.out.printf("%-6d %-50s %15d %15d %15d%n",
                        i + 1, displayClassName, instanceCount, totalSize, avgSize);
                } catch (Exception ex) {
                    // Ignore
                }
            }
        }
    }

    private class LeaksCommand implements Command {
        public String getName() { return "leaks"; }
        public String getDescription() { return "检测内存泄漏"; }
        public String getUsage() { return "leaks"; }
        public void execute(String[] args) {
            if (!attached || leakDetector == null) {
                System.err.println("未开始分析。请先使用 'start'。");
                return;
            }

            System.out.println("正在运行泄漏检测...");

            LeakReport report = leakDetector.detect();
            if (report == null || report.getCandidateCount() == 0) {
                System.out.println("未检测到潜在泄漏。");
                return;
            }

            LeakReport.Summary summary = report.getSummary();
            System.out.println("发现 " + summary.totalCandidates + " 个潜在泄漏:");
            System.out.println("  高严重性：" + summary.highSeverity);
            System.out.println("  中严重性：" + summary.mediumSeverity);
            System.out.println("  低严重性：" + summary.lowSeverity);
            System.out.println();

            System.out.printf("%-4s %-40s %10s %10s %8s %s%n",
                "#", "类", "实例数", "大小 (MB)", "严重性", "位置");
            System.out.println(new String(new char[90]).replace('\0', '-'));

            int i = 0;
            for (LeakDetector.LeakCandidate c : report.getTop(10)) {
                String className = c.className.length() > 38
                    ? "..." + c.className.substring(c.className.length() - 35)
                    : c.className;
                System.out.printf("%-4d %-40s %10d %10.2f %8d %s%n",
                    i + 1, className, c.instanceCount, c.totalSize / 1024.0 / 1024.0,
                    c.getSeverity(), c.allocationSite);
                i++;
            }

            System.out.println();
            System.out.println("建议:");
            for (String rec : report.getRecommendations()) {
                System.out.println("  • " + rec);
            }
        }
    }

    private class GcCommand implements Command {
        public String getName() { return "gc"; }
        public String getDescription() { return "显示 GC 统计"; }
        public String getUsage() { return "gc"; }
        public void execute(String[] args) {
            List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

            System.out.printf("%-25s %15s %15s %15s%n",
                "垃圾回收器", "回收次数", "总时间 (ms)", "平均暂停 (ms)");
            System.out.println(new String(new char[75]).replace('\0', '-'));

            for (GarbageCollectorMXBean bean : gcBeans) {
                long avgPause = bean.getCollectionCount() > 0
                    ? bean.getCollectionTime() / bean.getCollectionCount()
                    : 0;
                System.out.printf("%-25s %15d %15d %15d%n",
                    bean.getName(), bean.getCollectionCount(),
                    bean.getCollectionTime(), avgPause);
            }
        }
    }

    private class WatchCommand implements Command {
        public String getName() { return "watch"; }
        public String getDescription() { return "实时监控内存"; }
        public String getUsage() { return "watch [interval]"; }
        public void execute(String[] args) {
            int interval = 1000;
            if (args.length > 0) {
                try {
                    interval = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    // Use default
                }
            }

            System.out.println("正在监控内存使用 (Ctrl+C 停止)...");
            System.out.println();

            scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(() -> {
                Runtime runtime = Runtime.getRuntime();
                long used = runtime.totalMemory() - runtime.freeMemory();
                long max = runtime.maxMemory();
                double percent = (double) used / max * 100;

                System.out.printf("[%tT] 已用：%6d MB (%5.1f%%) | 空闲：%6d MB | 最大：%6d MB%n",
                    new Date(), used / 1024 / 1024, percent,
                    runtime.freeMemory() / 1024 / 1024, max / 1024 / 1024);
            }, 0, interval, TimeUnit.MILLISECONDS);
        }
    }

    private class ReportCommand implements Command {
        public String getName() { return "report"; }
        public String getDescription() { return "生成报告"; }
        public String getUsage() { return "report <html|json|csv> [output-file]"; }
        public void execute(String[] args) {
            if (!attached || heapAnalyzer == null) {
                System.err.println("未附着到任何进程。");
                return;
            }

            if (args.length < 1) {
                System.err.println("用法：report <html|json|csv> [output-file]");
                return;
            }

            String format = args[0].toLowerCase();
            String outputFile = args.length > 1 ? args[1] : "memory-report." + format;

            ReportGenerator generator = new ReportGenerator(heapAnalyzer);
            boolean success;

            switch (format) {
                case "html":
                    success = generator.generateHtmlReport(outputFile);
                    break;
                case "json":
                    success = generator.generateJsonReport(outputFile);
                    break;
                case "csv":
                    success = generator.generateCsvReport(outputFile);
                    break;
                default:
                    System.err.println("未知格式：" + format);
                    return;
            }

            if (success) {
                System.out.println("报告已生成：" + outputFile);
            } else {
                System.err.println("生成报告失败。");
            }
        }
    }

    private class ExitCommand implements Command {
        public String getName() { return "exit"; }
        public String getDescription() { return "退出程序"; }
        public String getUsage() { return "exit"; }
        public void execute(String[] args) {
            running.set(false);
            System.out.println("再见!");
        }
    }

    private class DebugCommand implements Command {
        public String getName() { return "debug"; }
        public String getDescription() { return "调试模式（生成测试数据）"; }
        public String getUsage() { return "debug <add-data|clear|stats>"; }
        public void execute(String[] args) {
            if (!attached || heapAnalyzer == null) {
                System.err.println("未附着到任何进程。请先使用 'attach <pid>'。");
                return;
            }

            if (args.length < 1) {
                System.out.println("用法：debug <add-data|clear|stats>");
                System.out.println();
                System.out.println("  add-data  - 添加模拟的分配数据（用于测试 histogram）");
                System.out.println("  clear     - 清除所有跟踪数据");
                System.out.println("  stats     - 显示内部统计信息");
                return;
            }

            String action = args[0].toLowerCase();
            switch (action) {
                case "add-data":
                    System.out.println("正在生成模拟分配数据...");
                    addMockData();
                    System.out.println("已生成模拟数据，现在可以使用 'histogram' 命令查看。");
                    break;

                case "clear":
                    heapAnalyzer.getObjectTracker().clear();
                    System.out.println("已清除所有跟踪数据。");
                    break;

                case "stats":
                    showInternalStats();
                    break;

                default:
                    System.err.println("未知动作：" + action);
                    System.err.println("用法：debug <add-data|clear|stats>");
            }
        }

        private void addMockData() {
            com.jvm.analyzer.heap.ObjectTracker tracker = heapAnalyzer.getObjectTracker();

            // 模拟各种类的分配
            addMockAllocations(tracker, "java.lang.String", 5000, 50);
            addMockAllocations(tracker, "java.util.HashMap$Node", 3000, 32);
            addMockAllocations(tracker, "java.util.ArrayList", 1000, 1024);
            addMockAllocations(tracker, "com.example.User", 500, 256);
            addMockAllocations(tracker, "com.example.Order", 300, 512);
            addMockAllocations(tracker, "java.util.concurrent.ConcurrentHashMap$Node", 2000, 64);
            addMockAllocations(tracker, "java.lang.StringBuilder", 800, 128);
            addMockAllocations(tracker, "com.example.cache.CacheEntry", 1500, 200);
        }

        private void addMockAllocations(com.jvm.analyzer.heap.ObjectTracker tracker,
                                       String className, int count, int size) {
            for (int i = 0; i < count; i++) {
                long objectId = System.nanoTime() + i;
                com.jvm.analyzer.core.AllocationRecord record =
                    new com.jvm.analyzer.core.AllocationRecord(
                        objectId,
                        className,
                        size,
                        System.currentTimeMillis() - (long)(Math.random() * 100000),
                        Thread.currentThread().getId(),
                        Thread.currentThread().getName(),
                        new StackTraceElement[] {
                            new StackTraceElement(className, "create", className.split("\\.")[2] + ".java", 42)
                        }
                    );
                tracker.track(record);
            }
        }

        private void showInternalStats() {
            com.jvm.analyzer.heap.ObjectTracker tracker = heapAnalyzer.getObjectTracker();
            Map<String, com.jvm.analyzer.heap.ObjectTracker.ClassInfo> stats = tracker.getClassStatistics();

            System.out.println("=== 内部统计信息 ===");
            System.out.println("跟踪中的对象数：" + tracker.getTrackedCount());
            System.out.println("累计跟踪对象数：" + tracker.getTotalTracked());
            System.out.println("已释放对象数：" + tracker.getTotalFreed());
            System.out.println("不同类的数量：" + stats.size());
            System.out.println();

            if (!stats.isEmpty()) {
                System.out.println("按总大小排序的前 10 个类:");
                List<com.jvm.analyzer.heap.ObjectTracker.ClassInfo> sorted = new ArrayList<>(stats.values());
                sorted.sort(java.util.Comparator.comparingLong(
                    (com.jvm.analyzer.heap.ObjectTracker.ClassInfo c) -> c.totalSize).reversed());

                for (int i = 0; i < Math.min(10, sorted.size()); i++) {
                    com.jvm.analyzer.heap.ObjectTracker.ClassInfo info = sorted.get(i);
                    System.out.printf("  %d. %s: %d 个实例，%d 字节%n",
                        i + 1, info.className, info.instanceCount, info.totalSize);
                }
            }
        }
    }
}
