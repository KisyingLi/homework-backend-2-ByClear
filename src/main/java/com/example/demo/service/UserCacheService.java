package com.example.demo.service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.demo.helper.RedisHelper;
import com.example.demo.model.UserModel;
import com.example.demo.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserCacheService {

	private static final Logger log = LoggerFactory.getLogger(UserCacheService.class);

	private final RedisHelper redis;
	private final UserRepository userRepository;

	/**
	 * Get user from cache or DB (Look-aside pattern)
	 */
	public UserModel getUser(Long userId) {
		UserModel cached = redis.getAsJson(redis.cacheUserKey(userId), UserModel.class);
		if (cached != null) return cached;

		UserModel user = userRepository.findById(userId).orElseThrow();
		cacheUserObject(user);
		return user;
	}

	/**
	 * Get user by username with cache
	 */
	public Optional<UserModel> getByUsername(String username) {
		String userIdStr = redis.get(redis.cacheUsernameKey(username));

		if (userIdStr != null) {
			return Optional.of(getUser(Long.parseLong(userIdStr)));
		}

		Optional<UserModel> userOpt = userRepository.findByUsername(username);
		userOpt.ifPresent(this::cacheUserObject);
		return userOpt;
	}

	/**
	 * Cache user object in both ID and Username mappings
	 */
	public void cacheUserObject(UserModel user) {
		redis.setAsJson(redis.cacheUserKey(user.getId()), user, 1, TimeUnit.HOURS);
		redis.set(redis.cacheUsernameKey(user.getUsername()), String.valueOf(user.getId()), 1, TimeUnit.HOURS);
	}

	/**
	 * Evict user cache
	 */
	public void evictUser(Long userId) {
		UserModel user = userRepository.findById(userId).orElse(null);
		redis.delete(redis.cacheUserKey(userId));
		if (user != null) {
			redis.delete(redis.cacheUsernameKey(user.getUsername()));
		}
		log.debug("Evicted user cache for: {}", userId);
	}
}
