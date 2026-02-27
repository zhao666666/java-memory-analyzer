package com.jvm.analyzer.core;

import java.util.*;
import java.util.concurrent.*;

/**
 * Allocation Record - Records details about a memory allocation
 *
 * Contains full call stack and context information.
 * Immutable and thread-safe.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class AllocationRecord {

    // 核心信息
    private final long objectId; // 对象唯一标识
    private final String className; // 类名（比如 java.lang.String）
    private final long size; // 占用字节数
    private final long timestamp; // 分配时间

    // 上下文信息
    private final long threadId; // 哪个线程分配的
    private final String threadName; // 线程名
    private final StackTraceElement[] stackTrace; // 调用栈！最关键！
    private final String allocationSite; // 分配位置（简化版）

    private final int hashCode;

    private static final ConcurrentHashMap<String, Integer> siteCounter = new ConcurrentHashMap<>();

    /**
     * Create allocation record
     *
     * @param objectId   Unique object identifier/tag
     * @param className  Class name of allocated object
     * @param size       Size in bytes
     * @param timestamp  Allocation timestamp
     * @param threadId   Allocating thread ID
     * @param threadName Allocating thread name
     * @param stackTrace Stack trace at allocation point
     */
    public AllocationRecord(long objectId, String className, long size, long timestamp, long threadId,
            String threadName, StackTraceElement[] stackTrace) {
        this.objectId = objectId;
        this.className = className;
        this.size = size;
        this.timestamp = timestamp;
        this.threadId = threadId;
        this.threadName = threadName;
        this.stackTrace = stackTrace != null ? Arrays.copyOf(stackTrace, stackTrace.length) : new StackTraceElement[0];
        this.allocationSite = buildAllocationSite(this.stackTrace);
        this.hashCode = Objects.hash(objectId, className);
    }

    /**
     * Build allocation site string from stack trace
     */
    private String buildAllocationSite(StackTraceElement[] trace) {
        if (trace == null || trace.length == 0) {
            return "unknown";
        }

        // Find first non-framework frame
        for (StackTraceElement element : trace) {
            String className = element.getClassName();
            // Skip internal JVM and analyzer frames 跳过 JVM 内部框架和我们自己的分析器代码
            if (!className.startsWith("sun.") &&
                    !className.startsWith("java.lang.") &&
                    !className.startsWith("com.jvm.analyzer.")) {
                return element.toString();
            }
        }

        // Fallback to first frame
        return trace[0].toString();
    }

    /**
     * Get object ID
     */
    public long getObjectId() {
        return objectId;
    }

    /**
     * Get class name
     */
    public String getClassName() {
        return className;
    }

    /**
     * Get allocation size in bytes
     */
    public long getSize() {
        return size;
    }

    /**
     * Get timestamp
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get thread ID
     */
    public long getThreadId() {
        return threadId;
    }

    /**
     * Get thread name
     */
    public String getThreadName() {
        return threadName;
    }

    /**
     * Get stack trace
     */
    public StackTraceElement[] getStackTrace() {
        return Arrays.copyOf(stackTrace, stackTrace.length);
    }

    /**
     * Get allocation site (short form)
     */
    public String getAllocationSite() {
        return allocationSite;
    }

    /**
     * Get allocation age in milliseconds
     */
    public long getAge() {
        return System.currentTimeMillis() - timestamp;
    }

    /**
     * Get allocation age in human-readable form
     */
    public String getAgeString() {
        return formatDuration(getAge());
    }

    /**
     * Get full stack trace as string
     */
    public String getStackTraceString() {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : stackTrace) {
            sb.append("\tat ").append(element).append("\n");
        }
        return sb.toString();
    }

    /**
     * Get short stack trace (top 5 frames)
     */
    public String getShortStackTrace() {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(5, stackTrace.length);
        for (int i = 0; i < limit; i++) {
            sb.append("\tat ").append(stackTrace[i]).append("\n");
        }
        if (stackTrace.length > 5) {
            sb.append("\t... ").append(stackTrace.length - 5).append(" more frames\n");
        }
        return sb.toString();
    }

    /**
     * Get allocation site key (for grouping)
     */
    public String getSiteKey() {
        return allocationSite;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        AllocationRecord that = (AllocationRecord) o;
        return objectId == that.objectId;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return String.format("AllocationRecord{obj=%d, class=%s, size=%d, site=%s, age=%s}",
                objectId, className, size, allocationSite, getAgeString());
    }

    /**
     * Format duration to human-readable string
     */
    public static String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + "ms";
        } else if (millis < 60000) {
            return String.format("%.1fs", millis / 1000.0);
        } else if (millis < 3600000) {
            return String.format("%.1fm", millis / 60000.0);
        } else {
            return String.format("%.1fh", millis / 3600000.0);
        }
    }

    /**
     * Create from current stack trace
     */
    public static AllocationRecord createFromCurrent(long objectId, String className, long size) {
        Throwable t = new Throwable();
        StackTraceElement[] fullTrace = t.getStackTrace();

        // Skip this method and caller frames
        int skip = 2;
        StackTraceElement[] trace = new StackTraceElement[Math.max(0, fullTrace.length - skip)];
        if (trace.length > 0) {
            System.arraycopy(fullTrace, skip, trace, 0, trace.length);
        }

        return new AllocationRecord(
                objectId,
                className,
                size,
                System.currentTimeMillis(),
                Thread.currentThread().getId(),
                Thread.currentThread().getName(),
                trace);
    }

    /**
     * Builder for AllocationRecord
     */
    public static class Builder {
        private long objectId;
        private String className;
        private long size;
        private long timestamp = System.currentTimeMillis();
        private long threadId = Thread.currentThread().getId();
        private String threadName = Thread.currentThread().getName();
        private StackTraceElement[] stackTrace;

        public Builder setObjectId(long id) {
            this.objectId = id;
            return this;
        }

        public Builder setClassName(String name) {
            this.className = name;
            return this;
        }

        public Builder setSize(long size) {
            this.size = size;
            return this;
        }

        public Builder setTimestamp(long ts) {
            this.timestamp = ts;
            return this;
        }

        public Builder setThreadId(long id) {
            this.threadId = id;
            return this;
        }

        public Builder setThreadName(String name) {
            this.threadName = name;
            return this;
        }

        public Builder setStackTrace(StackTraceElement[] trace) {
            this.stackTrace = trace;
            return this;
        }

        public Builder captureStackTrace() {
            Throwable t = new Throwable();
            this.stackTrace = t.getStackTrace();
            return this;
        }

        public Builder captureStackTrace(int skip) {
            Throwable t = new Throwable();
            StackTraceElement[] fullTrace = t.getStackTrace();
            int start = Math.min(skip, fullTrace.length);
            this.stackTrace = Arrays.copyOfRange(fullTrace, start, fullTrace.length);
            return this;
        }

        public AllocationRecord build() {
            return new AllocationRecord(objectId, className, size, timestamp,
                    threadId, threadName, stackTrace);
        }
    }
}
