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
            if (!attached || heapAnalyzer == null) {
                System.err.println("未附着到任何进程。");
                return;
            }

            int limit = 20;
            if (args.length > 0) {
                try {
                    limit = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    // Use default
                }
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
}
