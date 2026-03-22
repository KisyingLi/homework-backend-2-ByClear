package com.example.demo.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.config.RedisPubSubConfig;
import com.example.demo.enums.ActivityKey;
import com.example.demo.helper.RedisHelper;
import com.example.demo.model.ActivityMasterModel;
import com.example.demo.model.RewardRecordModel;
import com.example.demo.model.UserModel;
import com.example.demo.repository.ActivityMasterRepository;
import com.example.demo.repository.GameLaunchRecordRepository;
import com.example.demo.repository.RewardRecordRepository;
import com.example.demo.repository.UserLoginRecordRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.model.GameLaunchRecordModel;
import com.example.demo.model.UserLoginRecordModel;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MissionService {

	private static final Logger log = LoggerFactory.getLogger(MissionService.class);

	private final RedisHelper redis;
	private final UserRepository userRepository;
	private final ActivityMasterRepository activityMasterRepository;
	private final RewardRecordRepository rewardRecordRepository;
	private final UserCacheService userCacheService;
	private final UserLoginRecordRepository userLoginRecordRepository;
	private final GameLaunchRecordRepository gameLaunchRecordRepository;

	private static final ActivityKey DEFAULT_ACTIVITY = ActivityKey.NEW_USER_MISSION;

	// ─────────────────────────────────────────
	// Activity config cache (L1 Caffeine + L2 Redis)
	// ─────────────────────────────────────────

	@Cacheable(value = "activities", key = "#activityKey")
	public ActivityMasterModel getActivityConfig(String activityKey) {
		return getActivityConfigByRedis(activityKey);
	}

	/** Skip L1 cache — reads directly from Redis / DB */
	public ActivityMasterModel getActivityConfigByRedis(String activityKey) {
		String cacheKey = redis.activityConfigKey(activityKey);

		ActivityMasterModel cached = redis.getAsJson(cacheKey, ActivityMasterModel.class);
		if (cached != null) return cached;

		ActivityMasterModel activity = activityMasterRepository.findByActivityKey(activityKey).orElseThrow();
		redis.setAsJson(cacheKey, activity, 24, TimeUnit.HOURS);
		return activity;
	}

	@CacheEvict(value = "activities", key = "#activityKey")
	public void refreshActivityCache(String activityKey) {
		redis.delete(redis.activityConfigKey(activityKey));
		redis.publish(RedisPubSubConfig.ACTIVITY_CACHE_TOPIC, activityKey);
		log.info("Activity cache refreshed and sync message sent for: {}", activityKey);
	}

	// ─────────────────────────────────────────
	// Mission progress recording
	// ─────────────────────────────────────────

	public void recordLogin(Long userId, LocalDate loginDate) {
		if (isMissionExpired(userId)) {
			log.info("User {} mission expired, login not recorded.", userId);
			return;
		}

		// Save to DB for data compensation
		if (userLoginRecordRepository.findByUserIdAndLoginDate(userId, loginDate).isEmpty()) {
			userLoginRecordRepository.save(UserLoginRecordModel.builder()
					.userId(userId)
					.loginDate(loginDate)
					.build());
		}

		String key = redis.missionUserKey(userId, DEFAULT_ACTIVITY);
		String lastLoginStr = redis.hashGet(key, "last_login_date");

		int consecutiveDays = 0;
		String consecutiveDaysStr = redis.hashGet(key, "login_days");
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

		redis.hashPut(key, "last_login_date", loginDate.toString());
		redis.hashPut(key, "login_days", String.valueOf(consecutiveDays));

		log.info("User {} login recorded. Consecutive days: {}", userId, consecutiveDays);
		checkAllMissionsAndReward(userId, DEFAULT_ACTIVITY);
	}

	public void recordLaunch(Long userId, Long gameId) {
		if (isMissionExpired(userId)) return;

		// Save to DB for data compensation
		gameLaunchRecordRepository.save(GameLaunchRecordModel.builder()
				.userId(userId)
				.gameId(gameId)
				.build());

		String launchedKey = redis.launchedGamesKey(userId, DEFAULT_ACTIVITY);
		redis.setAdd(launchedKey, String.valueOf(gameId));
		Long distinctGamesCount = redis.setSize(launchedKey);

		if (distinctGamesCount != null && distinctGamesCount == 1) {
			UserModel user = userCacheService.getUser(userId);
			ActivityMasterModel activity = getActivityConfig(DEFAULT_ACTIVITY.getKey());
			LocalDateTime expirationTime = user.getCreatedAt().plusDays(activity.getDaysLimit());
			Duration ttl = Duration.between(LocalDateTime.now(), expirationTime);
			if (!ttl.isNegative() && !ttl.isZero()) {
				redis.expire(launchedKey, ttl);
			}
		}

		log.info("User {} launched game {}. Total distinct games launched: {}", userId, gameId, distinctGamesCount);

		checkAllMissionsAndReward(userId, DEFAULT_ACTIVITY);
	}

	public void recordPlay(Long userId, Long gameId, Integer score) {
		if (isMissionExpired(userId)) return;

		String key = redis.missionUserKey(userId, DEFAULT_ACTIVITY);
		long sessions = redis.hashIncrement(key, "play_sessions", 1);
		long totalScore = redis.hashIncrement(key, "total_score", score);

		log.info("User {} played game {} with score {}. Total sessions: {}, Total score: {}",
				userId, gameId, score, sessions, totalScore);

		checkAllMissionsAndReward(userId, DEFAULT_ACTIVITY);
	}

	// ─────────────────────────────────────────
	// Reward check
	// ─────────────────────────────────────────

	/**
	 * Read-phase: load progress from Redis and decide whether to grant reward.
	 * No DB connection is acquired here unless all missions are done.
	 * <p>
	 * Uses a single HGETALL + SCARD instead of multiple HGET calls to minimise Redis round-trips.
	 */
	public void checkAllMissionsAndReward(Long userId, ActivityKey activityKey) {
		String key = redis.missionUserKey(userId, activityKey);

		// 1. Activity config (Redis / DB fallback)
		ActivityMasterModel activity = getActivityConfigByRedis(activityKey.getKey());

		// 2. Fetch all hash fields in one HGETALL
		Map<String, String> progress = redis.hashGetAll(key);

		// 3. Fast-path guard: skip DB if already claimed
		if ("1".equals(progress.get("reward_claimed"))) return;

		// 4. Read progress values from the map (no extra Redis calls)
		int loginDays     = parseInt(progress.get("login_days"));
		int launchedGames = parseSize(redis.setSize(redis.launchedGamesKey(userId, activityKey)));
		int sessions      = parseInt(progress.get("play_sessions"));
		int totalScore    = parseInt(progress.get("total_score"));

		// 5. Check all mission targets
		boolean allDone = activity.getMissions().stream().allMatch(m -> switch (m.getMissionType()) {
			case LOGIN       -> loginDays     >= m.getTargetCount();
			case GAME_LAUNCH -> launchedGames >= m.getTargetCount();
			case GAME_PLAY   -> sessions      >= m.getTargetCount() && totalScore > m.getTargetScore();
		});

		if (!allDone) return;

		// 6. All missions done → hand off to the write phase
		grantReward(userId, activity);
	}

	/**
	 * Write-phase: all DB writes in one @Transactional method so they
	 * commit or roll back together.
	 * <p>
	 * Steps:
	 *   a. DB safety check (source of truth, in case Redis flag was lost)
	 *   b. Update user points
	 *   c. Persist RewardRecord
	 *   d. Mark Redis claimed flag (after DB commit)
	 */
	@Transactional
	public void grantReward(Long userId, ActivityMasterModel activity) {
		// a. DB safety check — prevents double-grant if Redis flag was evicted
		if (rewardRecordRepository.findByUserIdAndActivityId(userId, activity.getId()).isPresent()) return;

		log.info("User {} completed ALL MISSIONS! Awarding {} points.", userId, activity.getTotalReward());

		// b. Update user points
		UserModel user = userRepository.findById(userId).orElseThrow();
		user.setTotalPoints(user.getTotalPoints() + activity.getTotalReward());
		userRepository.save(user);

		// c. Persist reward record
		rewardRecordRepository.save(RewardRecordModel.builder()
				.userId(userId)
				.activityId(activity.getId())
				.rewardPoints(activity.getTotalReward())
				.build());

		// d. Evict user cache + mark Redis claimed flag
		//    (done last so Redis reflects DB state after successful commit)
		userCacheService.evictUser(userId);
		redis.hashPut(redis.missionUserKey(userId, activity.getActivityKey()), "reward_claimed", "1");
	}

	// ─────────────────────────────────────────
	// Helpers
	// ─────────────────────────────────────────

	private boolean isMissionExpired(Long userId) {
		String key = redis.missionUserKey(userId, DEFAULT_ACTIVITY);
		String cachedExpired = redis.hashGet(key, "expired");
		if ("true".equals(cachedExpired))  return true;
		if ("false".equals(cachedExpired)) return false;

		UserModel user = userCacheService.getUser(userId);
		ActivityMasterModel activity = getActivityConfig(DEFAULT_ACTIVITY.getKey());

		long daysPass = ChronoUnit.DAYS.between(user.getCreatedAt(), LocalDateTime.now());
		boolean expired = daysPass >= activity.getDaysLimit();

		redis.hashPut(key, "expired", String.valueOf(expired));

		// Set TTL dynamically based on user's registration + activity duration
		if (!expired) {
			LocalDateTime expirationTime = user.getCreatedAt().plusDays(activity.getDaysLimit());
			Duration ttl = Duration.between(LocalDateTime.now(), expirationTime);
			if (!ttl.isNegative() && !ttl.isZero()) {
				redis.expire(key, ttl);
			}
		} else {
			// If already expired, keep the cached 'expired=true' for 7 days
			redis.expire(key, Duration.ofDays(7));
		}

		return expired;
	}

	private static int parseInt(String value) {
		return value != null ? Integer.parseInt(value) : 0;
	}

	private static int parseSize(Long value) {
		return value != null ? value.intValue() : 0;
	}

	public Map<String, Object> getUserMissionProgress(Long userId) {
		UserModel user = userCacheService.getUser(userId);

		ActivityMasterModel activity = getActivityConfig(DEFAULT_ACTIVITY.getKey());
		if (activity == null) {
			throw new IllegalStateException("Activity configuration not found");
		}

		long daysPass = ChronoUnit.DAYS.between(user.getCreatedAt(), java.time.LocalDateTime.now());
		long daysRemaining = Math.max(0, activity.getDaysLimit() - daysPass);
		boolean isExpired = daysPass >= activity.getDaysLimit();

		boolean isClaimed = rewardRecordRepository.findByUserIdAndActivityId(userId, activity.getId()).isPresent();
		String key = redis.missionUserKey(userId, DEFAULT_ACTIVITY);

		if (!isClaimed) {
			String claimedStr = redis.hashGet(key, "reward_claimed");
			isClaimed = "1".equals(claimedStr);
		}

		int loginDays = parseInt(redis.hashGet(key, "login_days"));
		int launchedGames = parseSize(redis.setSize(redis.launchedGamesKey(userId, DEFAULT_ACTIVITY)));
		int sessions = parseInt(redis.hashGet(key, "play_sessions"));
		int totalScore = parseInt(redis.hashGet(key, "total_score"));

		var missionList = activity.getMissions().stream().map(m -> {
			boolean done = false;
			String progressText = "";
			switch (m.getMissionType()) {
				case LOGIN -> {
					done = loginDays >= m.getTargetCount();
					progressText = loginDays + " / " + m.getTargetCount();
				}
				case GAME_LAUNCH -> {
					done = launchedGames >= m.getTargetCount();
					progressText = launchedGames + " / " + m.getTargetCount();
				}
				case GAME_PLAY -> {
					done = (sessions >= m.getTargetCount() && totalScore > m.getTargetScore());
					progressText = String.format("Sessions: %d / %d, Total Score: %d / %d", sessions,
							m.getTargetCount(), totalScore,
							m.getTargetScore());
				}
			}
			return Map.of("id", m.getMissionType().getCode(), "name", m.getMissionName(), "progress", progressText, "status",
					done ? "COMPLETED" : "IN_PROGRESS");
		}).toList();

		return Map.of("userId", user.getId(), "username", user.getUsername(), "totalPoints",
				user.getTotalPoints(), "rewardClaimed", isClaimed, "daysRemaining", daysRemaining, "isExpired",
				isExpired, "missions", missionList);
	}
}
