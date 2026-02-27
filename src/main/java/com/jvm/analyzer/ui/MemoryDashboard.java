package com.jvm.analyzer.ui;

import com.jvm.analyzer.attach.*;
import com.jvm.analyzer.core.*;
import com.jvm.analyzer.heap.*;
import com.jvm.analyzer.leak.*;
import com.jvm.analyzer.report.*;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Memory Dashboard - Main GUI for memory analysis
 *
 * Provides real-time memory monitoring, leak detection,
 * and visualization capabilities.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class MemoryDashboard extends JFrame {

    private static final String TITLE = "Java 内存分析工具";
    private static final Dimension WINDOW_SIZE = new Dimension(1200, 800);

    // 核心组件
    private HeapAnalyzer heapAnalyzer;
    private LeakDetector leakDetector;
    private ProcessAttacher attacher;

    // UI 组件
    private JPanel mainPanel;
    private JTabbedPane tabbedPane;
    private JMenuItem attachMenuItem;
    private JMenuItem detachMenuItem;
    private JMenuItem snapshotMenuItem;
    private JMenuItem reportMenuItem;
    private JMenuItem exitMenuItem;
    private JLabel statusLabel;
    private JLabel connectionStatusLabel;
    private JLabel memoryUsageLabel;

    // 面板
    private MemoryOverviewPanel overviewPanel;
    private ClassHistogramPanel classHistogramPanel;
    private LeakDetectionPanel leakDetectionPanel;
    private AllocationTrackerPanel allocationTrackerPanel;

    // 状态
    private volatile int targetPid = -1;
    private volatile boolean attached = false;
    private volatile ExecutorService executor;
    private volatile ScheduledExecutorService scheduler;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    // 刷新定时器
    private ScheduledFuture<?> refreshTask;
    private static final long REFRESH_INTERVAL_MS = 1000;

    /**
     * 创建内存仪表盘
     */
    public MemoryDashboard() {
        setTitle(TITLE);
        setSize(WINDOW_SIZE);
        setMinimumSize(new Dimension(800, 600));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initializeComponents();
        createMenuBar();
        createMainPanel();
        createStatusBar();

        // 启动执行器
        executor = Executors.newCachedThreadPool();
        scheduler = Executors.newScheduledThreadPool(2);

        // 显示欢迎消息
        showStatus("就绪。请附着到进程开始分析。");
    }

    /**
     * 初始化核心组件
     */
    private void initializeComponents() {
        heapAnalyzer = new HeapAnalyzer();
        attacher = new ProcessAttacher();
    }

    /**
     * 创建菜单栏
     */
    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // 文件菜单
        JMenu fileMenu = new JMenu("文件");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        attachMenuItem = new JMenuItem("附着到进程...", KeyEvent.VK_A);
        attachMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK));
        attachMenuItem.addActionListener(e -> showAttachDialog());
        fileMenu.add(attachMenuItem);

        detachMenuItem = new JMenuItem("分离", KeyEvent.VK_D);
        detachMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK));
        detachMenuItem.setEnabled(false);
        detachMenuItem.addActionListener(e -> detach());
        fileMenu.add(detachMenuItem);

        fileMenu.addSeparator();

        snapshotMenuItem = new JMenuItem("获取快照", KeyEvent.VK_S);
        snapshotMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        snapshotMenuItem.setEnabled(false);
        snapshotMenuItem.addActionListener(e -> takeSnapshot());
        fileMenu.add(snapshotMenuItem);

        reportMenuItem = new JMenuItem("生成报告", KeyEvent.VK_G);
        reportMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK));
        reportMenuItem.setEnabled(false);
        reportMenuItem.addActionListener(e -> showReportDialog());
        fileMenu.add(reportMenuItem);

        fileMenu.addSeparator();

        exitMenuItem = new JMenuItem("退出", KeyEvent.VK_X);
        exitMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        exitMenuItem.addActionListener(e -> onExit());
        fileMenu.add(exitMenuItem);

        menuBar.add(fileMenu);

        // 分析菜单
        JMenu analysisMenu = new JMenu("分析");
        analysisMenu.setMnemonic(KeyEvent.VK_N);

        JMenuItem startAnalysisItem = new JMenuItem("开始分析");
        startAnalysisItem.addActionListener(e -> {
            if (heapAnalyzer != null && !heapAnalyzer.isAnalyzing()) {
                heapAnalyzer.startAnalysis();
                leakDetector = new LeakDetector(heapAnalyzer.getObjectTracker());
                leakDetector.start();
                startRefresh();
                showStatus("分析已启动。");
            }
        });
        analysisMenu.add(startAnalysisItem);

        JMenuItem stopAnalysisItem = new JMenuItem("停止分析");
        stopAnalysisItem.addActionListener(e -> {
            if (heapAnalyzer != null && heapAnalyzer.isAnalyzing()) {
                heapAnalyzer.stopAnalysis();
                if (leakDetector != null) {
                    leakDetector.stop();
                }
                stopRefresh();
                showStatus("分析已停止。");
            }
        });
        analysisMenu.add(stopAnalysisItem);

        analysisMenu.addSeparator();

        JMenuItem detectLeaksItem = new JMenuItem("立即检测泄漏");
        detectLeaksItem.addActionListener(e -> {
            if (leakDetector != null) {
                executor.submit(() -> {
                    LeakReport report = leakDetector.detect();
                    if (report != null && report.getCandidateCount() > 0) {
                        SwingUtilities.invokeLater(() -> {
                            tabbedPane.setSelectedIndex(2); // 切换到泄漏标签页
                            leakDetectionPanel.updateReport(report);
                        });
                    }
                });
            }
        });
        analysisMenu.add(detectLeaksItem);

        menuBar.add(analysisMenu);

        // 视图菜单
        JMenu viewMenu = new JMenu("视图");
        viewMenu.setMnemonic(KeyEvent.VK_V);

        JMenuItem refreshItem = new JMenuItem("刷新");
        refreshItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        refreshItem.addActionListener(e -> refresh());
        viewMenu.add(refreshItem);

        menuBar.add(viewMenu);

        // 帮助菜单
        JMenu helpMenu = new JMenu("帮助");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        JMenuItem aboutItem = new JMenuItem("关于");
        aboutItem.addActionListener(e -> showAboutDialog());
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);

        setJMenuBar(menuBar);
    }

    /**
     * 创建主面板
     */
    private void createMainPanel() {
        mainPanel = new JPanel(new BorderLayout());

        tabbedPane = new JTabbedPane();

        // 概览标签页
        overviewPanel = new MemoryOverviewPanel();
        tabbedPane.addTab("概览", overviewPanel);

        // 类直方图标签页
        classHistogramPanel = new ClassHistogramPanel();
        tabbedPane.addTab("类直方图", classHistogramPanel);

        // 泄漏检测标签页
        leakDetectionPanel = new LeakDetectionPanel();
        tabbedPane.addTab("泄漏检测", leakDetectionPanel);

        // 分配跟踪标签页
        allocationTrackerPanel = new AllocationTrackerPanel();
        tabbedPane.addTab("分配跟踪", allocationTrackerPanel);

        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        add(mainPanel);
    }

    /**
     * 创建状态栏
     */
    private void createStatusBar() {
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createEtchedBorder());

        connectionStatusLabel = new JLabel("  未连接");
        connectionStatusLabel.setForeground(Color.RED);
        statusPanel.add(connectionStatusLabel, BorderLayout.WEST);

        memoryUsageLabel = new JLabel("内存：-- ");
        statusPanel.add(memoryUsageLabel, BorderLayout.CENTER);

        statusLabel = new JLabel("  就绪");
        statusPanel.add(statusLabel, BorderLayout.EAST);

        mainPanel.add(statusPanel, BorderLayout.SOUTH);
    }

    /**
     * 附着到进程
     */
    public void attachToProcess(int pid) {
        targetPid = pid;
        attachToProcess(String.valueOf(pid));
    }

    /**
     * 附着到进程
     */
    private void attachToProcess(String pid) {
        executor.submit(() -> {
            showStatus("正在附着到进程 " + pid + "...");

            ProcessAttacher.AttachmentResult result = attacher.attach(pid);

            SwingUtilities.invokeLater(() -> {
                if (result.success) {
                    attached = true;
                    targetPid = Integer.parseInt(pid);
                    connectionStatusLabel.setText("  已连接到 PID: " + pid);
                    connectionStatusLabel.setForeground(Color.GREEN);
                    attachMenuItem.setEnabled(false);
                    detachMenuItem.setEnabled(true);
                    snapshotMenuItem.setEnabled(true);
                    reportMenuItem.setEnabled(true);
                    showStatus("成功附着到进程 " + pid);

                    // 自动开始分析
                    heapAnalyzer.startAnalysis();
                    leakDetector = new LeakDetector(heapAnalyzer.getObjectTracker());
                    leakDetector.start();
                    startRefresh();

                } else {
                    showStatus("附着失败：" + result.message);
                    JOptionPane.showMessageDialog(this,
                        "无法附着到进程：\n" + result.message,
                        "附着失败",
                        JOptionPane.ERROR_MESSAGE);
                }
            });
        });
    }

    /**
     * 从当前进程分离
     */
    private void detach() {
        if (!attached) return;

        stopRefresh();

        if (heapAnalyzer != null) {
            heapAnalyzer.stopAnalysis();
        }
        if (leakDetector != null) {
            leakDetector.stop();
        }

        attached = false;
        targetPid = -1;

        connectionStatusLabel.setText("  未连接");
        connectionStatusLabel.setForeground(Color.RED);
        attachMenuItem.setEnabled(true);
        detachMenuItem.setEnabled(false);
        snapshotMenuItem.setEnabled(false);
        reportMenuItem.setEnabled(false);

        heapAnalyzer = new HeapAnalyzer();
        overviewPanel.clear();
        classHistogramPanel.clear();
        leakDetectionPanel.clear();
        allocationTrackerPanel.clear();

        showStatus("已从进程分离。");
    }

    /**
     * 获取内存快照
     */
    private void takeSnapshot() {
        if (heapAnalyzer == null) return;

        executor.submit(() -> {
            MemorySnapshot snapshot = heapAnalyzer.takeSnapshot();
            SwingUtilities.invokeLater(() -> {
                showStatus("已获取快照：" + snapshot);
                classHistogramPanel.updateSnapshot(snapshot);
            });
        });
    }

    /**
     * 显示报告对话框
     */
    private void showReportDialog() {
        String[] options = {"HTML 报告", "JSON 报告", "CSV 报告", "取消"};
        int choice = JOptionPane.showOptionDialog(this,
            "选择报告格式：",
            "生成报告",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null, options, options[0]);

        if (choice < 0 || choice == 3) return;

        String format;
        switch (choice) {
            case 0: format = "html"; break;
            case 1: format = "json"; break;
            case 2: format = "csv"; break;
            default: return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setSelectedFile(new File("memory-report." + format));

        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File outputFile = fileChooser.getSelectedFile();
            generateReport(format, outputFile);
        }
    }

    /**
     * 生成报告
     */
    private void generateReport(String format, File outputFile) {
        executor.submit(() -> {
            try {
                ReportGenerator generator = new ReportGenerator(heapAnalyzer);

                boolean success;
                switch (format.toLowerCase()) {
                    case "html":
                        success = generator.generateHtmlReport(outputFile.getAbsolutePath());
                        break;
                    case "json":
                        success = generator.generateJsonReport(outputFile.getAbsolutePath());
                        break;
                    case "csv":
                        success = generator.generateCsvReport(outputFile.getAbsolutePath());
                        break;
                    default:
                        success = false;
                }

                SwingUtilities.invokeLater(() -> {
                    if (success) {
                        showStatus("报告已生成：" + outputFile.getAbsolutePath());
                        JOptionPane.showMessageDialog(this,
                            "报告已保存到：\n" + outputFile.getAbsolutePath(),
                            "报告已生成",
                            JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        showStatus("生成报告失败");
                        JOptionPane.showMessageDialog(this,
                            "生成报告失败",
                            "错误",
                            JOptionPane.ERROR_MESSAGE);
                    }
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    showStatus("生成报告时出错：" + e.getMessage());
                    JOptionPane.showMessageDialog(this,
                        "错误：" + e.getMessage(),
                        "错误",
                        JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    /**
     * 启动刷新定时器
     */
    private void startRefresh() {
        if (refreshTask != null) return;

        refreshTask = scheduler.scheduleAtFixedRate(() -> {
            if (!refreshing.getAndSet(true)) {
                try {
                    refresh();
                } finally {
                    refreshing.set(false);
                }
            }
        }, 0, REFRESH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止刷新定时器
     */
    private void stopRefresh() {
        if (refreshTask != null) {
            refreshTask.cancel(false);
            refreshTask = null;
        }
    }

    /**
     * 刷新 UI
     */
    private void refresh() {
        if (heapAnalyzer == null || !attached) return;

        // 更新内存使用
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
        long maxMb = runtime.maxMemory() / 1024 / 1024;
        memoryUsageLabel.setText(String.format("内存：%d MB / %d MB (%.1f%%)",
            usedMb, maxMb, (double) usedMb / maxMb * 100));

        // 更新面板
        overviewPanel.update();
        classHistogramPanel.update();
        allocationTrackerPanel.update();

        // 定期泄漏检测
        if (leakDetector != null && System.currentTimeMillis() % 10000 < 1000) {
            LeakReport report = leakDetector.detect();
            if (report != null && report.getCandidateCount() > 0) {
                leakDetectionPanel.updateReport(report);
            }
        }
    }

    /**
     * 显示附着对话框
     */
    private void showAttachDialog() {
        List<ProcessAttacher.ProcessInfo> processes = ProcessAttacher.listJavaProcesses();

        if (processes.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "未找到 Java 进程。",
                "无进程",
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 创建带进程列表的对话框
        JDialog dialog = new JDialog(this, "选择进程", true);
        dialog.setSize(600, 400);
        dialog.setLocationRelativeTo(this);

        JPanel panel = new JPanel(new BorderLayout());

        // 进程表格
        String[] columns = {"PID", "显示名称", "主类"};
        Object[][] data = new Object[processes.size()][3];
        for (int i = 0; i < processes.size(); i++) {
            ProcessAttacher.ProcessInfo p = processes.get(i);
            data[i][0] = p.pid;
            data[i][1] = p.displayName;
            data[i][2] = p.mainClass;
        }

        JTable table = new JTable(data, columns);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scrollPane = new JScrollPane(table);
        panel.add(scrollPane, BorderLayout.CENTER);

        // 按钮
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton attachButton = new JButton("附着");
        attachButton.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                String pid = (String) data[table.convertRowIndexToModel(row)][0];
                dialog.dispose();
                attachToProcess(pid);
            }
        });
        buttonPanel.add(attachButton);

        JButton cancelButton = new JButton("取消");
        cancelButton.addActionListener(e -> dialog.dispose());
        buttonPanel.add(cancelButton);

        panel.add(buttonPanel, BorderLayout.SOUTH);
        dialog.add(panel);

        dialog.setVisible(true);
    }

    /**
     * 显示关于对话框
     */
    private void showAboutDialog() {
        String message = "Java 内存分析工具 v1.0.0\n\n" +
                "专业的 Java 进程内存分析工具。\n\n" +
                "功能特性：\n" +
                "- 实时内存监控\n" +
                "- 对象分配跟踪\n" +
                "- 内存泄漏检测\n" +
                "- 快照对比\n" +
                "- 多格式报告\n\n" +
                "JVM: " + System.getProperty("java.version") + "\n" +
                "操作系统：" + System.getProperty("os.name") + " " + System.getProperty("os.version");

        JOptionPane.showMessageDialog(this,
            message,
            "关于 Java 内存分析工具",
            JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * 显示状态消息
     */
    private void showStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("  " + message);
            statusLabel.setToolTipText(message);
        });
    }

    /**
     * 显示错误消息
     */
    public void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this,
                message,
                "错误",
                JOptionPane.ERROR_MESSAGE);
            showStatus("错误：" + message);
        });
    }

    /**
     * 处理退出
     */
    private void onExit() {
        // 清理
        stopRefresh();

        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (executor != null) {
            executor.shutdownNow();
        }

        // 如果已附着则分离
        if (attached) {
            detach();
        }

        System.exit(0);
    }

    @Override
    public void dispose() {
        onExit();
        super.dispose();
    }

    // ========================================================================
    // 内部面板类
    // ========================================================================

    /**
     * 内存概览面板
     */
    private class MemoryOverviewPanel extends JPanel {
        private final JLabel heapUsedLabel;
        private final JLabel heapCommittedLabel;
        private final JLabel heapMaxLabel;
        private final JLabel objectCountLabel;
        private final JLabel allocationCountLabel;
        private final JProgressBar heapProgressBar;

        public MemoryOverviewPanel() {
            setLayout(new BorderLayout());

            JPanel topPanel = new JPanel(new GridLayout(2, 1, 10, 10));
            topPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

            // 堆信息
            JPanel heapPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
            heapPanel.setBorder(BorderFactory.createTitledBorder("堆内存"));

            heapUsedLabel = new JLabel("已用：-- MB");
            heapUsedLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
            heapPanel.add(heapUsedLabel);

            heapCommittedLabel = new JLabel("已提交：-- MB");
            heapCommittedLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
            heapPanel.add(heapCommittedLabel);

            heapMaxLabel = new JLabel("最大：-- MB");
            heapMaxLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
            heapPanel.add(heapMaxLabel);

            topPanel.add(heapPanel);

            // 统计
            JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
            statsPanel.setBorder(BorderFactory.createTitledBorder("统计"));

            objectCountLabel = new JLabel("跟踪对象数：--");
            statsPanel.add(objectCountLabel);

            allocationCountLabel = new JLabel("分配次数：--");
            statsPanel.add(allocationCountLabel);

            topPanel.add(statsPanel);

            add(topPanel, BorderLayout.NORTH);

            // 进度条
            heapProgressBar = new JProgressBar(0, 100);
            heapProgressBar.setStringPainted(true);
            heapProgressBar.setPreferredSize(new Dimension(400, 30));
            heapProgressBar.setValue(0);

            JPanel barPanel = new JPanel(new FlowLayout());
            barPanel.add(heapProgressBar);
            add(barPanel, BorderLayout.CENTER);
        }

        public void update() {
            if (heapAnalyzer == null) return;

            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            long max = runtime.maxMemory();

            SwingUtilities.invokeLater(() -> {
                heapUsedLabel.setText(String.format("已用：%,d MB", used / 1024 / 1024));
                heapCommittedLabel.setText(String.format("已提交：%,d MB", runtime.totalMemory() / 1024 / 1024));
                heapMaxLabel.setText(String.format("最大：%,d MB", max / 1024 / 1024));

                int percent = (int) ((double) used / max * 100);
                heapProgressBar.setValue(percent);

                if (percent > 90) {
                    heapProgressBar.setForeground(Color.RED);
                } else if (percent > 70) {
                    heapProgressBar.setForeground(Color.ORANGE);
                } else {
                    heapProgressBar.setForeground(Color.GREEN);
                }

                long objectCount = heapAnalyzer.getObjectTracker().getTrackedCount();
                long allocCount = heapAnalyzer.getObjectTracker().getTotalTracked();
                objectCountLabel.setText(String.format("跟踪对象数：%,d", objectCount));
                allocationCountLabel.setText(String.format("分配次数：%,d", allocCount));
            });
        }

        public void clear() {
            SwingUtilities.invokeLater(() -> {
                heapUsedLabel.setText("已用：-- MB");
                heapCommittedLabel.setText("已提交：-- MB");
                heapMaxLabel.setText("最大：-- MB");
                heapProgressBar.setValue(0);
                objectCountLabel.setText("跟踪对象数：--");
                allocationCountLabel.setText("分配次数：--");
            });
        }
    }

    /**
     * 类直方图面板
     */
    private class ClassHistogramPanel extends JPanel {
        private final JTable histogramTable;
        private final DefaultTableModel tableModel;

        public ClassHistogramPanel() {
            setLayout(new BorderLayout());

            String[] columns = {"类名", "实例数", "总大小 (字节)", "平均大小 (字节)"};
            tableModel = new DefaultTableModel(columns, 0) {
                @Override
                public Class<?> getColumnClass(int columnIndex) {
                    if (columnIndex == 1 || columnIndex == 2 || columnIndex == 3) {
                        return Long.class;
                    }
                    return String.class;
                }
            };

            histogramTable = new JTable(tableModel);
            histogramTable.setAutoCreateRowSorter(true);

            JScrollPane scrollPane = new JScrollPane(histogramTable);
            add(scrollPane, BorderLayout.CENTER);

            // 工具栏
            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));

            JButton refreshButton = new JButton("刷新");
            refreshButton.addActionListener(e -> refreshHistogram());
            toolbar.add(refreshButton);

            JButton snapshotButton = new JButton("快照");
            snapshotButton.addActionListener(e -> takeSnapshot());
            toolbar.add(snapshotButton);

            add(toolbar, BorderLayout.NORTH);
        }

        public void update() {
            if (heapAnalyzer == null) return;

            Map<String, ObjectTracker.ClassInfo> stats =
                heapAnalyzer.getObjectTracker().getClassStatistics();

            if (stats.isEmpty()) return;

            // 更新表格（节流）
            if (System.currentTimeMillis() % 5000 < 1000) {
                refreshHistogram();
            }
        }

        private void refreshHistogram() {
            if (heapAnalyzer == null) return;

            Map<String, ObjectTracker.ClassInfo> stats =
                heapAnalyzer.getObjectTracker().getClassStatistics();

            List<ObjectTracker.ClassInfo> sorted = new ArrayList<>(stats.values());
            sorted.sort(Comparator.comparingLong((ObjectTracker.ClassInfo c) -> c.totalSize).reversed());

            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);

                int limit = Math.min(100, sorted.size());
                for (int i = 0; i < limit; i++) {
                    ObjectTracker.ClassInfo info = sorted.get(i);
                    tableModel.addRow(new Object[]{
                        info.className,
                        (long) info.instanceCount,
                        info.totalSize,
                        info.avgSize
                    });
                }
            });
        }

        public void updateSnapshot(MemorySnapshot snapshot) {
            // 已获取快照
            showStatus("已获取快照：" + snapshot.getSnapshotId());
        }

        public void clear() {
            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);
            });
        }
    }

    /**
     * 泄漏检测面板
     */
    private class LeakDetectionPanel extends JPanel {
        private final JTable leakTable;
        private final DefaultTableModel tableModel;
        private final JTextArea recommendationsArea;

        public LeakDetectionPanel() {
            setLayout(new BorderLayout());

            // 泄漏表格
            String[] columns = {"类名", "实例数", "大小 (MB)", "类型", "严重程度", "位置"};
            tableModel = new DefaultTableModel(columns, 0) {
                @Override
                public Class<?> getColumnClass(int columnIndex) {
                    if (columnIndex == 1) return Integer.class;
                    if (columnIndex == 2) return Double.class;
                    if (columnIndex == 4) return Integer.class;
                    return String.class;
                }
            };

            leakTable = new JTable(tableModel);
            leakTable.setAutoCreateRowSorter(true);

            JScrollPane tableScroll = new JScrollPane(leakTable);
            tableScroll.setPreferredSize(new Dimension(0, 200));
            add(tableScroll, BorderLayout.CENTER);

            // 建议
            JPanel bottomPanel = new JPanel(new BorderLayout());
            bottomPanel.setBorder(BorderFactory.createTitledBorder("建议"));

            recommendationsArea = new JTextArea(5, 0);
            recommendationsArea.setEditable(false);
            recommendationsArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            recommendationsArea.setLineWrap(true);

            JScrollPane recScroll = new JScrollPane(recommendationsArea);
            bottomPanel.add(recScroll, BorderLayout.CENTER);

            add(bottomPanel, BorderLayout.SOUTH);

            // 工具栏
            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));

            JButton detectButton = new JButton("立即检测");
            detectButton.addActionListener(e -> {
                if (leakDetector != null) {
                    executor.submit(() -> {
                        LeakReport report = leakDetector.detect();
                        if (report != null) {
                            SwingUtilities.invokeLater(() -> updateReport(report));
                        }
                    });
                }
            });
            toolbar.add(detectButton);

            add(toolbar, BorderLayout.NORTH);
        }

        public void updateReport(LeakReport report) {
            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);

                for (LeakDetector.LeakCandidate candidate : report.getTop(20)) {
                    tableModel.addRow(new Object[]{
                        candidate.className,
                        candidate.instanceCount,
                        String.format("%.2f", candidate.totalSize / 1024.0 / 1024.0),
                        candidate.type.getDescription(),
                        candidate.getSeverity(),
                        truncateSite(candidate.allocationSite, 50)
                    });
                }

                // 更新建议
                StringBuilder sb = new StringBuilder();
                for (String rec : report.getRecommendations()) {
                    sb.append("• ").append(rec).append("\n\n");
                }
                recommendationsArea.setText(sb.toString());
            });
        }

        private String truncateSite(String site, int maxLen) {
            if (site == null || site.length() <= maxLen) return site;
            return site.substring(0, maxLen - 3) + "...";
        }

        public void clear() {
            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);
                recommendationsArea.setText("");
            });
        }
    }

    /**
     * 分配跟踪面板
     */
    private class AllocationTrackerPanel extends JPanel {
        private final JTable allocationTable;
        private final DefaultTableModel tableModel;

        public AllocationTrackerPanel() {
            setLayout(new BorderLayout());

            String[] columns = {"对象 ID", "类名", "大小 (字节)", "线程", "位置", "存活时间"};
            tableModel = new DefaultTableModel(columns, 0);

            allocationTable = new JTable(tableModel);
            allocationTable.setAutoCreateRowSorter(true);

            JScrollPane scrollPane = new JScrollPane(allocationTable);
            add(scrollPane, BorderLayout.CENTER);
        }

        public void update() {
            if (heapAnalyzer == null) return;

            List<AllocationRecord> records = heapAnalyzer.getRecentAllocations(50);

            if (records.isEmpty()) return;

            // 节流更新
            if (System.currentTimeMillis() % 2000 < 1000) {
                SwingUtilities.invokeLater(() -> {
                    tableModel.setRowCount(0);

                    for (AllocationRecord record : records) {
                        tableModel.addRow(new Object[]{
                            record.getObjectId(),
                            record.getClassName(),
                            record.getSize(),
                            record.getThreadName(),
                            truncateSite(record.getAllocationSite(), 40),
                            record.getAgeString()
                        });
                    }
                });
            }
        }

        private String truncateSite(String site, int maxLen) {
            if (site == null || site.length() <= maxLen) return site;
            return site.substring(0, maxLen - 3) + "...";
        }

        public void clear() {
            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);
            });
        }
    }
}
