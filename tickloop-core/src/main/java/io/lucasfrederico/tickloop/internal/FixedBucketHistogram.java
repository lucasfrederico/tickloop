package io.lucasfrederico.tickloop.internal;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Log-scale fixed-bucket histogram for nanosecond-range latencies.
 *
 * <p>64 buckets, each spanning a power-of-two range:
 * <ul>
 *   <li>bucket 0: values in [1, 2)</li>
 *   <li>bucket 1: values in [2, 4)</li>
 *   <li>bucket 2: values in [4, 8)</li>
 *   <li>...</li>
 *   <li>bucket 62: values in [2^62, 2^63)</li>
 * </ul>
 *
 * <p>Properties:
 * <ul>
 *   <li><b>Lock-free</b> writes via {@link AtomicLongArray#incrementAndGet}.</li>
 *   <li><b>Zero allocation</b> on the hot path.</li>
 *   <li><b>O(1) record</b>, O(64) percentile query.</li>
 *   <li><b>Precision: 2x</b> — percentile result is the upper bound of the
 *       bucket containing the percentile mark, so it can over-report by up
 *       to a factor of 2. Good enough for "is p99 latency 10ms or 100ms?";
 *       not enough for HFT-grade tail latency analysis.</li>
 * </ul>
 *
 * <p>For tighter precision, use HdrHistogram (3 significant digits) or a
 * dedicated histogram library — they cost a runtime dep that this library
 * deliberately avoids.
 */
public final class FixedBucketHistogram {

    private static final int BUCKETS = 64;

    private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);

    public void record(long value) {
        int idx;
        if (value <= 1L) {
            idx = 0;
        } else {
            // floor(log2(value)) for positive long. For value=2 → 1, value=3 → 1, value=4 → 2.
            idx = 63 - Long.numberOfLeadingZeros(value);
            if (idx >= BUCKETS) idx = BUCKETS - 1;
        }
        counts.incrementAndGet(idx);
    }

    /**
     * Returns the upper bound of the bucket containing the requested
     * percentile (e.g. {@code percentile(0.99)} returns p99).
     *
     * @param p percentile in [0, 1]
     * @return nanosecond upper bound of the bucket, or 0 if no samples recorded
     */
    public long percentile(double p) {
        if (p < 0.0 || p > 1.0) {
            throw new IllegalArgumentException("p must be in [0,1], got " + p);
        }
        long total = 0;
        long[] snapshot = new long[BUCKETS];
        for (int i = 0; i < BUCKETS; i++) {
            long c = counts.get(i);
            snapshot[i] = c;
            total += c;
        }
        if (total == 0) return 0L;
        // For p=0 ("min"), target = 1 so we return the smallest non-empty bucket.
        // For p=1 ("max"), target = total so we return the largest non-empty bucket.
        long target = Math.max(1L, (long) Math.ceil(total * p));
        long cumulative = 0;
        for (int i = 0; i < BUCKETS; i++) {
            cumulative += snapshot[i];
            if (cumulative >= target) {
                // Upper bound of bucket i = 2^(i+1) - 1, approximate with 2^(i+1).
                return i >= 62 ? Long.MAX_VALUE : (1L << (i + 1));
            }
        }
        return Long.MAX_VALUE;
    }

    /** Total number of samples recorded. */
    public long totalSamples() {
        long total = 0;
        for (int i = 0; i < BUCKETS; i++) total += counts.get(i);
        return total;
    }
}
