package com.jvm.analyzer.core;

import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import java.util.*;
import java.util.function.*;

/**
 * Thread-Safe Counter - High-performance atomic counter with statistics
 *
 * Provides thread-safe counting operations with minimal contention.
 * Uses LongAdder for high-throughput scenarios.
 *
 * @author Java Memory Analyzer Team
 * @version 1.0.0
 */
public class ThreadSafeCounter {

    private final LongAdder count = new LongAdder();
    private final LongAdder sum = new LongAdder();
    private final LongAdder sumSquared = new LongAdder();
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong max = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong first = new AtomicLong(0);
    private final AtomicLong last = new AtomicLong(0);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Increment count by 1
     */
    public void increment() {
        count.increment();
    }

    /**
     * Increment count by specified amount
     */
    public void add(long value) {
        count.increment();
        sum.add(value);
        sumSquared.add(value * value);

        // Update min/max atomically
        updateMin(value);
        updateMax(value);

        // Update first/last
        if (!initialized.getAndSet(true)) {
            first.set(value);
        }
        last.set(value);
    }

    private void updateMin(long value) {
        long currentMin = min.get();
        while (value < currentMin) {
            if (min.compareAndSet(currentMin, value)) {
                break;
            }
            currentMin = min.get();
        }
    }

    private void updateMax(long value) {
        long currentMax = max.get();
        while (value > currentMax) {
            if (max.compareAndSet(currentMax, value)) {
                break;
            }
            currentMax = max.get();
        }
    }

    /**
     * Get current count
     */
    public long getCount() {
        return count.sum();
    }

    /**
     * Get sum of all values
     */
    public long getSum() {
        return sum.sum();
    }

    /**
     * Get average value
     */
    public double getAverage() {
        long c = count.sum();
        return c > 0 ? (double) sum.sum() / c : 0.0;
    }

    /**
     * Get minimum value
     */
    public long getMin() {
        return initialized.get() ? min.get() : 0;
    }

    /**
     * Get maximum value
     */
    public long getMax() {
        return initialized.get() ? max.get() : 0;
    }

    /**
     * Get first value
     */
    public long getFirst() {
        return first.get();
    }

    /**
     * Get last value
     */
    public long getLast() {
        return last.get();
    }

    /**
     * Get standard deviation
     */
    public double getStdDev() {
        long c = count.sum();
        if (c < 2) return 0.0;

        double mean = getAverage();
        double variance = (double) sumSquared.sum() / c - mean * mean;
        return Math.sqrt(Math.max(0, variance));
    }

    /**
     * Reset counter
     */
    public void reset() {
        count.reset();
        sum.reset();
        sumSquared.reset();
        min.set(Long.MAX_VALUE);
        max.set(Long.MIN_VALUE);
        first.set(0);
        last.set(0);
        initialized.set(false);
    }

    /**
     * Get statistics summary
     */
    public Stats getStats() {
        return new Stats(
            getCount(),
            getSum(),
            getAverage(),
            getMin(),
            getMax(),
            getStdDev(),
            getFirst(),
            getLast()
        );
    }

    @Override
    public String toString() {
        return String.format("Counter{count=%d, sum=%d, avg=%.2f, min=%d, max=%d}",
            getCount(), getSum(), getAverage(), getMin(), getMax());
    }

    /**
     * Statistics holder
     */
    public static class Stats {
        public final long count;
        public final long sum;
        public final double average;
        public final long min;
        public final long max;
        public final double stddev;
        public final long first;
        public final long last;

        public Stats(long count, long sum, double average, long min, long max,
                    double stddev, long first, long last) {
            this.count = count;
            this.sum = sum;
            this.average = average;
            this.min = min;
            this.max = max;
            this.stddev = stddev;
            this.first = first;
            this.last = last;
        }

        @Override
        public String toString() {
            return String.format("Stats{count=%d, sum=%d, avg=%.2f, min=%d, max=%d, stddev=%.2f}",
                count, sum, average, min, max, stddev);
        }
    }

    /**
     * Counter map for per-key counting
     */
    public static class CounterMap<K> {
        private final ConcurrentHashMap<K, ThreadSafeCounter> counters = new ConcurrentHashMap<>();
        private final boolean createIfAbsent;

        public CounterMap() {
            this(true);
        }

        public CounterMap(boolean createIfAbsent) {
            this.createIfAbsent = createIfAbsent;
        }

        public void add(K key, long value) {
            ThreadSafeCounter counter = getCounter(key);
            if (counter != null) {
                counter.add(value);
            }
        }

        public void increment(K key) {
            ThreadSafeCounter counter = getCounter(key);
            if (counter != null) {
                counter.increment();
            }
        }

        private ThreadSafeCounter getCounter(K key) {
            ThreadSafeCounter counter = counters.get(key);
            if (counter == null && createIfAbsent) {
                counter = new ThreadSafeCounter();
                ThreadSafeCounter existing = counters.putIfAbsent(key, counter);
                if (existing != null) {
                    counter = existing;
                }
            }
            return counter;
        }

        public ThreadSafeCounter get(K key) {
            return counters.get(key);
        }

        public Set<K> keys() {
            return Collections.unmodifiableSet(counters.keySet());
        }

        public Collection<ThreadSafeCounter> counters() {
            return Collections.unmodifiableCollection(counters.values());
        }

        public List<Map.Entry<K, Long>> getSortedBySum(int limit) {
            List<Map.Entry<K, Long>> entries = new ArrayList<>();
            for (Map.Entry<K, ThreadSafeCounter> entry : counters.entrySet()) {
                entries.add(new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue().getSum()));
            }
            entries.sort(Map.Entry.<K, Long>comparingByValue().reversed());
            return entries.subList(0, Math.min(limit, entries.size()));
        }

        public void clear() {
            counters.clear();
        }
    }
}
