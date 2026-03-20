package com.example.demo.service;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.example.demo.model.UserModel;
import com.example.demo.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserCacheService {

	private static final Logger log = LoggerFactory.getLogger(UserCacheService.class);

	private final StringRedisTemplate redisTemplate;
	private final UserRepository userRepository;
	private final ObjectMapper objectMapper;

	private static final String CACHE_USER_PREFIX = "cache:user:";
	private static final String CACHE_USERNAME_PREFIX = "cache:username:";

	/**
	 * Get user from cache or DB (Look-aside pattern)
	 */
	public UserModel getUser(Long userId) {
		String cacheKey = CACHE_USER_PREFIX + userId;
		UserModel user = getFromCache(cacheKey);
		if (user != null) return user;

		user = userRepository.findById(userId).orElseThrow();
		cacheUserObject(user);
		return user;
	}

	/**
	 * Get user by username with cache
	 */
	public java.util.Optional<UserModel> getByUsername(String username) {
		String nameKey = CACHE_USERNAME_PREFIX + username;
		String userIdStr = redisTemplate.opsForValue().get(nameKey);

		if (userIdStr != null) {
			return java.util.Optional.of(getUser(Long.parseLong(userIdStr)));
		}

		java.util.Optional<UserModel> userOpt = userRepository.findByUsername(username);
		userOpt.ifPresent(this::cacheUserObject);
		return userOpt;
	}

	/**
	 * Helper to cache user object in both ID and Username mappings
	 */
	public void cacheUserObject(UserModel user) {
		try {
			String json = objectMapper.writeValueAsString(user);
			redisTemplate.opsForValue().set(CACHE_USER_PREFIX + user.getId(), json, 1, TimeUnit.HOURS);
			redisTemplate.opsForValue().set(CACHE_USERNAME_PREFIX + user.getUsername(), String.valueOf(user.getId()), 1, TimeUnit.HOURS);
		} catch (JsonProcessingException e) {
			log.error("Failed to serialize user for cache: " + user.getId(), e);
		}
	}

	private UserModel getFromCache(String key) {
		String json = redisTemplate.opsForValue().get(key);
		if (json == null) return null;
		try {
			return objectMapper.readValue(json, UserModel.class);
		} catch (JsonProcessingException e) {
			log.error("Failed to deserialize user cache for key: " + key, e);
			return null;
		}
	}

	/**
	 * Evict user cache
	 */
	public void evictUser(Long userId) {
		UserModel user = userRepository.findById(userId).orElse(null);
		redisTemplate.delete(CACHE_USER_PREFIX + userId);
		if (user != null) {
			redisTemplate.delete(CACHE_USERNAME_PREFIX + user.getUsername());
		}
		log.debug("Evicted user cache for: {}", userId);
	}
}
