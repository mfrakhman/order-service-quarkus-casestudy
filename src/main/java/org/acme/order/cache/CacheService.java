package org.acme.order.cache;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

/**
 * Plain string cache — one Get, one Set, raw text in and out. Callers own
 * their own keys, TTLs, and any JSON conversion (via ObjectMapper) — this
 * class only knows how to talk to Redis.
 *
 * Two things added on top of the simplest possible version, both load-bearing
 * (ARCHITECTURE.md §7.4):
 * - the try/catch: without it, a Redis outage would throw all the way up and
 *   fail the request instead of degrading gracefully — confirmed by actually
 *   stopping Redis and testing (a plain try/catch around a slow/hung call
 *   wasn't even enough on its own; see quarkus.redis.timeout in
 *   application.properties, which bounds how long a command waits before
 *   giving up).
 * - setex instead of set: a plain set() never expires, so a cached value
 *   would never refresh. The TTL is the only staleness bound this project has
 *   (no cache invalidation anywhere) — dropping it means serving a stale
 *   price forever.
 */
@Singleton
public class CacheService {

    private static final Logger LOG = Logger.getLogger(CacheService.class);

    private final ValueCommands<String, String> commands;

    @Inject
    public CacheService(RedisDataSource redis) {
        this.commands = redis.value(String.class);
    }

    public String get(String key) {
        try {
            return commands.get(key);
        } catch (RuntimeException e) {
            LOG.warnf("Redis GET %s failed, treating as miss: %s", key, e.getMessage());
            return null;
        }
    }

    public void set(String key, String value, int ttlSeconds) {
        try {
            commands.setex(key, ttlSeconds, value);
        } catch (RuntimeException e) {
            LOG.warnf("Redis SET %s failed (non-fatal): %s", key, e.getMessage());
        }
    }
}
