package com.example.demo.controller;

import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.enums.ActivityKey;
import com.example.demo.model.ActivityMasterModel;
import com.example.demo.model.UserModel;
import com.example.demo.repository.RewardRecordRepository;
import com.example.demo.service.AuthService;
import com.example.demo.service.MissionService;
import com.example.demo.service.UserCacheService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Mission System", description = "Provides APIs for mission progress queries and reward claims")
public class MissionController {

	private final StringRedisTemplate redisTemplate;
	private final UserCacheService userCacheService;
	private final RewardRecordRepository rewardRecordRepository;
	private final AuthService authService;
	private final MissionService missionService;

	@GetMapping("/missions")
	@Operation(summary = "Query mission progress", description = "Calculates a 30-day deadline based on the user's registration time, and dynamically retrieves task completion status from the database and Redis")
	public ResponseEntity<?> getMissions(@RequestHeader(value = "Authorization", required = false) String token) {
		Long userId = authService.getUserIdFromToken(token);
		if (userId == null) {
			return ResponseEntity.status(401).body(Map.of("error", "Unauthorized: Invalid or missing token"));
		}

		UserModel user;
		try {
			user = userCacheService.getUser(userId);
		} catch (Exception e) {
			return ResponseEntity.status(404).body(Map.of("error", "User not found"));
		}

		String key = "mission:user:" + userId;

		// Read activity configuration (using Service's cache path)
		ActivityMasterModel activity = missionService.getActivityConfig(ActivityKey.NEW_USER_MISSION.getKey());
		if (activity == null) {
			return ResponseEntity.status(500).body(Map.of("error", "Activity configuration not found"));
		}

		// Calculate deadline
		long daysPass = ChronoUnit.DAYS.between(user.getCreatedAt(), java.time.LocalDateTime.now());
		long daysRemaining = Math.max(0, activity.getDaysLimit() - daysPass);
		boolean isExpired = daysPass >= activity.getDaysLimit();

		// Check reward record in database first
		boolean isClaimed = rewardRecordRepository.findByUserIdAndActivityId(userId, activity.getId()).isPresent();

		// If no record in DB, check Redis (double insurance)
		if (!isClaimed) {
			String claimedStr = (String) redisTemplate.opsForHash().get(key, "reward_claimed");
			isClaimed = "1".equals(claimedStr);
		}

		// Fetch all progress
		String loginDaysStr = (String) redisTemplate.opsForHash().get(key, "login_days");
		int loginDays = loginDaysStr != null ? Integer.parseInt(loginDaysStr) : 0;

		String setKey = "mission:user:" + userId + ":launched_games";
		Long launchedCount = redisTemplate.opsForSet().size(setKey);
		int launchedGames = launchedCount != null ? launchedCount.intValue() : 0;

		String sessionsStr = (String) redisTemplate.opsForHash().get(key, "play_sessions");
		int sessions = sessionsStr != null ? Integer.parseInt(sessionsStr) : 0;

		String totalScoreStr = (String) redisTemplate.opsForHash().get(key, "total_score");
		int totalScore = totalScoreStr != null ? Integer.parseInt(totalScoreStr) : 0;

		// Dynamically organize status based on configuration
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

		Map<String, Object> response = Map.of("userId", user.getId(), "username", user.getUsername(), "totalPoints",
				user.getTotalPoints(), "rewardClaimed", isClaimed, "daysRemaining", daysRemaining, "isExpired",
				isExpired, "missions", missionList);

		return ResponseEntity.ok(response);
	}

	@GetMapping("/admin/refresh-cache")
	@Operation(summary = "Refresh activity configuration cache", description = "Manually clear and reload the activity configuration cache in Redis")
	public ResponseEntity<?> refreshCache() {
		missionService.refreshActivityCache(ActivityKey.NEW_USER_MISSION.getKey());
		return ResponseEntity.ok(Map.of("message", "Activity cache refreshed successfully"));
	}
}
