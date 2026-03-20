package com.example.demo.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.config.RedisPubSubConfig;
import com.example.demo.enums.ActivityKey;
import com.example.demo.model.ActivityMasterModel;
import com.example.demo.model.RewardRecordModel;
import com.example.demo.model.UserModel;
import com.example.demo.repository.ActivityMasterRepository;
import com.example.demo.repository.RewardRecordRepository;
import com.example.demo.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MissionService {

	private static final Logger log = LoggerFactory.getLogger(MissionService.class);

	private final StringRedisTemplate redisTemplate;
	private final UserRepository userRepository;
	private final ActivityMasterRepository activityMasterRepository;
	private final RewardRecordRepository rewardRecordRepository;
	private final ObjectMapper objectMapper;
	private final UserCacheService userCacheService;

	private static final ActivityKey DEFAULT_ACTIVITY = ActivityKey.NEW_USER_MISSION;
	private static final String CACHE_ACTIVITY_PREFIX = "config:activity:";

	// Check if the mission has expired
	private boolean isMissionExpired(Long userId) {
		String cacheKey = "mission:user:" + userId + ":expired";
		String cachedExpired = redisTemplate.opsForValue().get(cacheKey);

		if ("true".equals(cachedExpired))
			return true;
		if ("false".equals(cachedExpired))
			return false;

		UserModel user = userCacheService.getUser(userId);
		ActivityMasterModel activity = getActivityConfig(DEFAULT_ACTIVITY.getKey());

		long daysPass = ChronoUnit.DAYS.between(user.getCreatedAt(), LocalDateTime.now());
		boolean expired = daysPass >= activity.getDaysLimit();

		redisTemplate.opsForValue().set(cacheKey, String.valueOf(expired), 1, TimeUnit.HOURS);
		return expired;
	}

	// Get activity configuration (cache first: L1 Caffeine -> L2 Redis)
	@Cacheable(value = "activities", key = "#activityKey")
	public ActivityMasterModel getActivityConfig(String activityKey) {
		return getActivityConfigByRedis(activityKey);
	}

	// Get activity configuration directly from Redis/DB (Skip L1 Cache for safety check)
	public ActivityMasterModel getActivityConfigByRedis(String activityKey) {
		String cacheKey = CACHE_ACTIVITY_PREFIX + activityKey;
		String cachedJson = redisTemplate.opsForValue().get(cacheKey);

		if (cachedJson != null) {
			try {
				return objectMapper.readValue(cachedJson, ActivityMasterModel.class);
			} catch (JsonProcessingException e) {
				log.error("Failed to deserialize activity cache for key: " + activityKey, e);
			}
		}

		ActivityMasterModel activity = activityMasterRepository.findByActivityKey(activityKey).orElseThrow();

		try {
			String json = objectMapper.writeValueAsString(activity);
			redisTemplate.opsForValue().set(cacheKey, json, 24, java.util.concurrent.TimeUnit.HOURS);
		} catch (JsonProcessingException e) {
			log.error("Failed to serialize activity for cache: " + activityKey, e);
		}

		return activity;
	}

	// Refresh cache (Redis L2 + Sync other L1s)
	@CacheEvict(value = "activities", key = "#activityKey")
	public void refreshActivityCache(String activityKey) {
		// 1. Clear Redis L2
		redisTemplate.delete(CACHE_ACTIVITY_PREFIX + activityKey);

		// 2. Broadcast refresh message to other instances
		redisTemplate.convertAndSend(RedisPubSubConfig.ACTIVITY_CACHE_TOPIC, activityKey);

		log.info("Activity cache refreshed and sync message sent for: {}", activityKey);
	}

	// Record login days (Mission 1)
	public void recordLogin(Long userId, LocalDate loginDate) {
		if (isMissionExpired(userId)) {
			log.info("User {} mission expired, login not recorded.", userId);
			return;
		}

		String key = "mission:user:" + userId;
		String lastLoginStr = (String) redisTemplate.opsForHash().get(key, "last_login_date");

		int consecutiveDays = 0;
		String consecutiveDaysStr = (String) redisTemplate.opsForHash().get(key, "login_days");
		if (consecutiveDaysStr != null) {
			consecutiveDays = Integer.parseInt(consecutiveDaysStr);
		}

		if (lastLoginStr == null) {
			consecutiveDays = 1;
		} else {
			LocalDate lastLogin = LocalDate.parse(lastLoginStr);
			long daysBetween = ChronoUnit.DAYS.between(lastLogin, loginDate);

			if (daysBetween == 1) {
				consecutiveDays++;
			} else if (daysBetween > 1) {
				consecutiveDays = 1;
			}
		}

		redisTemplate.opsForHash().put(key, "last_login_date", loginDate.toString());
		redisTemplate.opsForHash().put(key, "login_days", String.valueOf(consecutiveDays));

		log.info("User {} login recorded. Consecutive days: {}", userId, consecutiveDays);
		checkAllMissionsAndReward(userId, DEFAULT_ACTIVITY);
	}

	// Record game launch (Mission 2)
	public void recordLaunch(Long userId, Long gameId) {
		if (isMissionExpired(userId))
			return;

		String setKey = "mission:user:" + userId + ":launched_games";
		redisTemplate.opsForSet().add(setKey, String.valueOf(gameId));

		Long distinctGamesCount = redisTemplate.opsForSet().size(setKey);
		log.info("User {} launched game {}. Total distinct games launched: {}", userId, gameId, distinctGamesCount);

		checkAllMissionsAndReward(userId, DEFAULT_ACTIVITY);
	}

	// Record game play sessions and score (Mission 3)
	public void recordPlay(Long userId, Long gameId, Integer score) {
		if (isMissionExpired(userId))
			return;

		String key = "mission:user:" + userId;
		long sessions = redisTemplate.opsForHash().increment(key, "play_sessions", 1);
		long totalScore = redisTemplate.opsForHash().increment(key, "total_score", score);

		log.info("User {} played game {} with score {}. Total sessions: {}, Total score: {}", userId, gameId, score,
				sessions, totalScore);

		checkAllMissionsAndReward(userId, DEFAULT_ACTIVITY);
	}

	// Unified rewarding logic
	@Transactional
	public void checkAllMissionsAndReward(Long userId, ActivityKey activityKey) {
		String key = "mission:user:" + userId;

		// 1. Get activity config (Redis cache first, DB fallback)
		ActivityMasterModel activity = getActivityConfigByRedis(activityKey.getKey());

		// 2. Redis fast-path guard: check reward_claimed flag BEFORE hitting DB
		String claimed = (String) redisTemplate.opsForHash().get(key, "reward_claimed");
		if ("1".equals(claimed)) {
			return;
		}

		// 3. Fetch all progress from Redis (before DB connection is acquired)
		String loginDaysStr = (String) redisTemplate.opsForHash().get(key, "login_days");
		int loginDays = loginDaysStr != null ? Integer.parseInt(loginDaysStr) : 0;

		String setKey = "mission:user:" + userId + ":launched_games";
		Long launchedCount = redisTemplate.opsForSet().size(setKey);
		int launchedGames = launchedCount != null ? launchedCount.intValue() : 0;

		String sessionsStr = (String) redisTemplate.opsForHash().get(key, "play_sessions");
		int sessions = sessionsStr != null ? Integer.parseInt(sessionsStr) : 0;

		String totalScoreStr = (String) redisTemplate.opsForHash().get(key, "total_score");
		int totalScore = totalScoreStr != null ? Integer.parseInt(totalScoreStr) : 0;

		// 4. Dynamically determine if all targets are met
		var missions = activity.getMissions();
		boolean allDone = true;
		for (var m : missions) {
			boolean currentDone = false;
			switch (m.getMissionType()) {
			case LOGIN -> currentDone = loginDays >= m.getTargetCount();
			case GAME_LAUNCH -> currentDone = launchedGames >= m.getTargetCount();
			case GAME_PLAY -> currentDone = (sessions >= m.getTargetCount() && totalScore > m.getTargetScore());
			}
			if (!currentDone) {
				allDone = false;
				break;
			}
		}

		// Early return: no DB access needed if missions are not done
		if (!allDone) return;

		// 5. DB safety check (Source of Truth) - only reached when allDone = true
		if (rewardRecordRepository.findByUserIdAndActivityId(userId, activity.getId()).isPresent()) {
			return;
		}

		// 6. DB writes (connection acquired here, minimizing hold time)
		log.info("User {} completed ALL MISSIONS! Awarding {} points.", userId, activity.getTotalReward());

		UserModel user = userRepository.findById(userId).orElseThrow();
		updateUserPoints(user, activity.getTotalReward());

		RewardRecordModel record = RewardRecordModel.builder()
				.userId(userId)
				.activityId(activity.getId())
				.rewardPoints(activity.getTotalReward())
				.build();
		rewardRecordRepository.save(record);

		// 7. Mark Redis reward flag after DB writes succeed
		redisTemplate.opsForHash().put(key, "reward_claimed", "1");
	}
	// Update user points and evict cache
	@Transactional
	public void updateUserPoints(UserModel user, int rewardPoints) {
		user.setTotalPoints(user.getTotalPoints() + rewardPoints);
		userRepository.save(user);

		// Evict Cache via UserCacheService
		userCacheService.evictUser(user.getId());
	}
}
