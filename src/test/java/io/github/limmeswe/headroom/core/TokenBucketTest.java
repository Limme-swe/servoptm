package io.github.limmeswe.headroom.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenBucketTest {

    @Test
    void refillsAgainstMonotonicTime() {
        TokenBucket bucket = new TokenBucket(10.0, 10.0, 1_000_000_000L);
        for (int index = 0; index < 10; index++) {
            assertTrue(bucket.tryConsume(1.0, 1_000_000_000L));
        }
        assertFalse(bucket.tryConsume(1.0, 1_000_000_000L));
        assertTrue(bucket.tryConsume(1.0, 1_100_000_000L));
    }
}
