package com.qwertyblob.every1luvs.service;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process, per-key fixed-window rate limiter.
 *
 * <p>State lives in a local {@link ConcurrentHashMap}, so limits are enforced per JVM. This
 * is correct for the single-instance docker-compose deployment this app targets. If it is
 * ever scaled to multiple replicas behind a load balancer, each replica would keep its own
 * counters and the effective limit would multiply by the replica count — at that point move
 * the buckets to a shared store (e.g. Redis) so the window is global.
 */
@Service
public class RateLimiterService {

    private record BucketEntry(long count, long windowStartMs) {}

    private final ConcurrentHashMap<String, BucketEntry> buckets = new ConcurrentHashMap<>();

    /**
     * @param key         namespaced key, e.g. "login:user@example.com"
     * @param maxAttempts maximum requests allowed within the window
     * @param windowMs    window duration in milliseconds
     * @param message     error message returned on 429
     */
    public void check(String key, int maxAttempts, long windowMs, String message) {
        long now = System.currentTimeMillis();
        long[] slot = {0};

        buckets.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMs() >= windowMs) {
                slot[0] = 1;
                return new BucketEntry(1, now);
            }
            long next = existing.count() + 1;
            slot[0] = next;
            return new BucketEntry(next, existing.windowStartMs());
        });

        if (slot[0] > maxAttempts) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
    }

    public void reset(String key) {
        buckets.remove(key);
    }

    @Scheduled(fixedDelay = 300_000)
    void evictExpired() {
        long cutoff = System.currentTimeMillis() - 3_600_000L;
        buckets.entrySet().removeIf(e -> e.getValue().windowStartMs() < cutoff);
    }
}
