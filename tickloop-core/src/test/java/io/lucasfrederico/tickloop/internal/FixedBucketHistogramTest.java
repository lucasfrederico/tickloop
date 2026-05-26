package io.lucasfrederico.tickloop.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixedBucketHistogramTest {

    @Test
    void empty_histogram_returns_zero_for_any_percentile() {
        FixedBucketHistogram h = new FixedBucketHistogram();
        assertThat(h.percentile(0.5)).isEqualTo(0L);
        assertThat(h.percentile(0.99)).isEqualTo(0L);
        assertThat(h.totalSamples()).isEqualTo(0L);
    }

    @Test
    void uniform_distribution_p50_is_around_median() {
        FixedBucketHistogram h = new FixedBucketHistogram();
        // Insert 100 samples evenly: 1, 2, 4, 8, ... 2^99. p50 should be ~2^49 range.
        // Simpler: insert 100 samples of value 1000 (bucket 9). p50 should be in
        // that bucket → returns 2^10 = 1024 (upper bound of bucket 9).
        for (int i = 0; i < 100; i++) {
            h.record(1000L);
        }
        assertThat(h.percentile(0.5)).isEqualTo(1024L); // 2^10, upper bound of bucket 9
        assertThat(h.totalSamples()).isEqualTo(100L);
    }

    @Test
    void p99_picks_up_tail_correctly() {
        FixedBucketHistogram h = new FixedBucketHistogram();
        // 100 normal samples in low bucket + 5 outliers in high bucket = 105 total.
        // p95 = ceil(105 * 0.95) = 100 → still in low bucket.
        // p99 = ceil(105 * 0.99) = 104 → tail samples needed → high bucket.
        for (int i = 0; i < 100; i++) h.record(100L);      // bucket 6
        for (int i = 0; i < 5; i++)  h.record(1_000_000L); // bucket 19

        long p50 = h.percentile(0.5);
        long p95 = h.percentile(0.95);
        long p99 = h.percentile(0.99);

        assertThat(p50).as("p50 in low bucket").isLessThanOrEqualTo(128L);
        assertThat(p95).as("p95 still in low bucket (100/105 = 95.2%)").isLessThanOrEqualTo(128L);
        assertThat(p99)
                .as("p99 must reflect the tail (5/105 outliers exceed 99th percentile threshold)")
                .isGreaterThanOrEqualTo(1_000_000L);
    }

    @Test
    void records_negative_or_zero_as_smallest_bucket() {
        FixedBucketHistogram h = new FixedBucketHistogram();
        h.record(0L);
        h.record(-100L);
        h.record(1L);
        assertThat(h.totalSamples()).isEqualTo(3L);
        // All in bucket 0 → percentile returns 2 (upper bound of bucket 0).
        assertThat(h.percentile(0.5)).isEqualTo(2L);
    }

    @Test
    void rejects_out_of_range_percentile() {
        FixedBucketHistogram h = new FixedBucketHistogram();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> h.percentile(-0.1));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> h.percentile(1.5));
    }
}
