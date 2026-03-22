package com.example.demo.helper;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.example.demo.enums.ActivityKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * Centralized Redis helper.
 * <p>
 * All Redis key definitions live here so that naming conventions are enforced
 * in one place and callers never construct raw strings.
 */
@Component
@RequiredArgsConstructor
public class RedisHelper {

    private static final Logger log = LoggerFactory.getLogger(RedisHelper.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────
    // Key builders
    // ─────────────────────────────────────────

    /** Token generated at login. Value = userId */
    public String authTokenKey(String token) {
        return "auth:token:" + token;
    }

    /** Play session created at game launch. Value = "userId:gameId" */
    public String playSessionKey(String playToken) {
        return "play:session:" + playToken;
    }

    /**
     * Mission progress hash for a user scoped to a specific activity.
     * Key: mission:user:{userId}:{activityKey}
     */
    public String missionUserKey(Long userId, String activityKey) {
        return "mission:user:" + userId + ":" + activityKey;
    }

    /** Convenience overload accepting {@link ActivityKey} enum. */
    public String missionUserKey(Long userId, ActivityKey activityKey) {
        return missionUserKey(userId, activityKey.getKey());
    }

    /**
     * Set of distinct game IDs the user has launched under a specific activity.
     * Key: mission:user:{userId}:{activityKey}:launched_games
     */
    public String launchedGamesKey(Long userId, String activityKey) {
        return "mission:user:" + userId + ":" + activityKey + ":launched_games";
    }

    /** Convenience overload accepting {@link ActivityKey} enum. */
    public String launchedGamesKey(Long userId, ActivityKey activityKey) {
        return launchedGamesKey(userId, activityKey.getKey());
    }

    /** Activity config JSON. Value = serialised ActivityMasterModel */
    public String activityConfigKey(String activityKey) {
        return "config:activity:" + activityKey;
    }

    /** User object cached by userId. Value = serialised UserModel */
    public String cacheUserKey(Long userId) {
        return "cache:user:" + userId;
    }

    /** Pointer from username → userId */
    public String cacheUsernameKey(String username) {
        return "cache:username:" + username;
    }

    // ─────────────────────────────────────────
    // Generic operations
    // ─────────────────────────────────────────

    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public void set(String key, String value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    public void set(String key, String value, long amount, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, amount, unit);
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }

    public void expire(String key, Duration ttl) {
        redisTemplate.expire(key, ttl);
    }

    /** Serialise an object to JSON and store it with a TTL. */
    public <T> void setAsJson(String key, T value, long amount, TimeUnit unit) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, amount, unit);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise object for Redis key [{}]: {}", key, e.getMessage());
        }
    }

    /** Retrieve JSON from Redis and deserialise to the target type. Returns null on miss or error. */
    public <T> T getAsJson(String key, Class<T> type) {
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialise Redis key [{}] into {}: {}", key, type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────
    // Hash operations (mission progress)
    // ─────────────────────────────────────────

    public String hashGet(String key, String field) {
        return (String) redisTemplate.opsForHash().get(key, field);
    }

    /** Fetch all fields of a hash in one HGETALL call. Returns an empty map if the key does not exist. */
    public Map<String, String> hashGetAll(String key) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
        return raw.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        e -> (String) e.getKey(),
                        e -> (String) e.getValue()
                ));
    }

    public void hashPut(String key, String field, String value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    public long hashIncrement(String key, String field, long delta) {
        return redisTemplate.opsForHash().increment(key, field, delta);
    }

    // ─────────────────────────────────────────
    // Set operations (launched games)
    // ─────────────────────────────────────────

    public void setAdd(String key, String value) {
        redisTemplate.opsForSet().add(key, value);
    }

    public Long setSize(String key) {
        return redisTemplate.opsForSet().size(key);
    }

    // ─────────────────────────────────────────
    // Pub/Sub
    // ─────────────────────────────────────────

    public void publish(String channel, String message) {
        redisTemplate.convertAndSend(channel, message);
    }
}
