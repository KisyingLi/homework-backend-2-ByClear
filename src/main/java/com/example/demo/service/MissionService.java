package com.example.demo.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.ActivityMaster;
import com.example.demo.entity.User;
import com.example.demo.repository.ActivityMasterRepository;
import com.example.demo.repository.RewardRecordRepository;
import com.example.demo.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MissionService {

	private static final Logger log = LoggerFactory.getLogger(MissionService.class);

	private final StringRedisTemplate redisTemplate;
	private final UserRepository userRepository;
	private final ActivityMasterRepository activityMasterRepository;
	private final RewardRecordRepository rewardRecordRepository;

	private static final String ACTIVITY_KEY = "NEW_USER_MISSION";

	// 檢查任務是否過期
	private boolean isMissionExpired(Long userId) {
		String cacheKey = "mission:user:" + userId + ":expired";
		String cachedExpired = redisTemplate.opsForValue().get(cacheKey);

		if ("true".equals(cachedExpired))
			return true;
		if ("false".equals(cachedExpired))
			return false;

		User user = userRepository.findById(userId).orElseThrow();
		ActivityMaster activity = activityMasterRepository.findByActivityKey(ACTIVITY_KEY).orElseThrow();

		long daysPass = ChronoUnit.DAYS.between(user.getCreatedAt(), java.time.LocalDateTime.now());
		boolean expired = daysPass >= activity.getDaysLimit();

		redisTemplate.opsForValue().set(cacheKey, String.valueOf(expired), 1, java.util.concurrent.TimeUnit.HOURS);
		return expired;
	}

	// 記錄登入天數 (Mission 1)
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
		checkAllMissionsAndReward(userId);
	}

	// 記錄遊戲啟動 (Mission 2)
	public void recordLaunch(Long userId, Long gameId) {
		if (isMissionExpired(userId))
			return;

		String setKey = "mission:user:" + userId + ":launched_games";
		redisTemplate.opsForSet().add(setKey, String.valueOf(gameId));

		Long distinctGamesCount = redisTemplate.opsForSet().size(setKey);
		log.info("User {} launched game {}. Total distinct games launched: {}", userId, gameId, distinctGamesCount);

		checkAllMissionsAndReward(userId);
	}

	// 記錄遊戲遊玩次數與積分 (Mission 3)
	public void recordPlay(Long userId, Long gameId, Integer score) {
		if (isMissionExpired(userId))
			return;

		String key = "mission:user:" + userId;
		long sessions = redisTemplate.opsForHash().increment(key, "play_sessions", 1);
		long totalScore = redisTemplate.opsForHash().increment(key, "total_score", score);

		log.info("User {} played game {} with score {}. Total sessions: {}, Total score: {}", userId, gameId, score,
				sessions, totalScore);

		checkAllMissionsAndReward(userId);
	}

	// 統一的發獎邏輯
	@Transactional
	public void checkAllMissionsAndReward(Long userId) {
		String key = "mission:user:" + userId;

		// 1. 優先檢查資料庫領獎紀錄
		ActivityMaster activity = activityMasterRepository.findByActivityKey(ACTIVITY_KEY).orElseThrow();
		if (rewardRecordRepository.findByUserIdAndActivityId(userId, activity.getId()).isPresent()) {
			return;
		}

		// 2. 檢查 Redis 快取 (作為第一層過濾)
		String claimed = (String) redisTemplate.opsForHash().get(key, "reward_claimed");
		if ("1".equals(claimed)) {
			return;
		}

		// 抓出所有進度
		var missions = activity.getMissions();
		String loginDaysStr = (String) redisTemplate.opsForHash().get(key, "login_days");
		int loginDays = loginDaysStr != null ? Integer.parseInt(loginDaysStr) : 0;

		String setKey = "mission:user:" + userId + ":launched_games";
		Long launchedCount = redisTemplate.opsForSet().size(setKey);
		int launchedGames = launchedCount != null ? launchedCount.intValue() : 0;

		String sessionsStr = (String) redisTemplate.opsForHash().get(key, "play_sessions");
		int sessions = sessionsStr != null ? Integer.parseInt(sessionsStr) : 0;

		String totalScoreStr = (String) redisTemplate.opsForHash().get(key, "total_score");
		int totalScore = totalScoreStr != null ? Integer.parseInt(totalScoreStr) : 0;

		// 3. 動態判斷是否達標
		boolean allDone = true;
		for (var m : missions) {
			boolean currentDone = false;
			switch (m.getMissionOrder()) {
			case 1 -> currentDone = loginDays >= m.getTargetCount();
			case 2 -> currentDone = launchedGames >= m.getTargetCount();
			case 3 -> currentDone = (sessions >= m.getTargetCount() && totalScore > m.getTargetScore());
			}
			if (!currentDone) {
				allDone = false;
				break;
			}
		}

		if (allDone) {
			log.info("🎉 User {} completed ALL MISSIONS! Awarding {} points.", userId, activity.getTotalReward());

			// 4. 更新使用者分數
			User user = userRepository.findById(userId).orElseThrow();
			user.setTotalPoints(user.getTotalPoints() + activity.getTotalReward());
			userRepository.save(user);

			// 5. 寫入領獎紀錄 (持久化)
			com.example.demo.entity.RewardRecord record = com.example.demo.entity.RewardRecord.builder().userId(userId)
					.activityId(activity.getId()).rewardPoints(activity.getTotalReward()).build();
			rewardRecordRepository.save(record);

			// 6. 更新 Redis 標記
			redisTemplate.opsForHash().put(key, "reward_claimed", "1");
		}
	}
}
