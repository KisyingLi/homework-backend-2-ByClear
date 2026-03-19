package com.example.demo.controller;

import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.example.demo.entity.ActivityMaster;
import com.example.demo.entity.User;
import com.example.demo.repository.ActivityMasterRepository;
import com.example.demo.repository.RewardRecordRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AuthService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "任務系統", description = "提供任務進度查詢與獎勵領取相關 API")
public class MissionController {

	private final StringRedisTemplate redisTemplate;
	private final UserRepository userRepository;
	private final ActivityMasterRepository activityMasterRepository;
	private final RewardRecordRepository rewardRecordRepository;
	private final AuthService authService;

	@GetMapping("/missions")
	@Operation(summary = "查詢任務進度", description = "根據用戶註冊時間計算 30 天期限，並從資料庫與 Redis 動態獲取各項任務完成狀態")
	public ResponseEntity<?> getMissions(@RequestHeader(value = "Authorization", required = false) String token) {
		Long userId = authService.getUserIdFromToken(token);
		if (userId == null) {
			return ResponseEntity.status(401).body(Map.of("error", "Unauthorized: Invalid or missing token"));
		}

		User user = userRepository.findById(userId).orElse(null);
		if (user == null) {
			return ResponseEntity.status(404).body(Map.of("error", "User not found"));
		}

		String key = "mission:user:" + userId;

		// 讀取活動配置
		ActivityMaster activity = activityMasterRepository.findByActivityKey("NEW_USER_MISSION").orElse(null);
		if (activity == null) {
			return ResponseEntity.status(500).body(Map.of("error", "Activity configuration not found"));
		}

		// 計算期限
		long daysPass = ChronoUnit.DAYS.between(user.getCreatedAt(), java.time.LocalDateTime.now());
		long daysRemaining = Math.max(0, activity.getDaysLimit() - daysPass);
		boolean isExpired = daysPass >= activity.getDaysLimit();

		// 優先從資料庫檢查領獎紀錄
		boolean isClaimed = rewardRecordRepository.findByUserIdAndActivityId(userId, activity.getId()).isPresent();

		// 如果資料庫沒紀錄，再看 Redis (雙重保險)
		if (!isClaimed) {
			String claimedStr = (String) redisTemplate.opsForHash().get(key, "reward_claimed");
			isClaimed = "1".equals(claimedStr);
		}

		// 抓出所有進度
		String loginDaysStr = (String) redisTemplate.opsForHash().get(key, "login_days");
		int loginDays = loginDaysStr != null ? Integer.parseInt(loginDaysStr) : 0;

		String setKey = "mission:user:" + userId + ":launched_games";
		Long launchedCount = redisTemplate.opsForSet().size(setKey);
		int launchedGames = launchedCount != null ? launchedCount.intValue() : 0;

		String sessionsStr = (String) redisTemplate.opsForHash().get(key, "play_sessions");
		int sessions = sessionsStr != null ? Integer.parseInt(sessionsStr) : 0;

		String totalScoreStr = (String) redisTemplate.opsForHash().get(key, "total_score");
		int totalScore = totalScoreStr != null ? Integer.parseInt(totalScoreStr) : 0;

		// 根據配置動態整理狀態
		var missionList = activity.getMissions().stream().map(m -> {
			boolean done = false;
			String progressText = "";
			switch (m.getMissionOrder()) {
			case 1 -> {
				done = loginDays >= m.getTargetCount();
				progressText = loginDays + " / " + m.getTargetCount();
			}
			case 2 -> {
				done = launchedGames >= m.getTargetCount();
				progressText = launchedGames + " / " + m.getTargetCount();
			}
			case 3 -> {
				done = (sessions >= m.getTargetCount() && totalScore > m.getTargetScore());
				progressText = String.format("次數: %d / %d, 總分: %d / %d", sessions, m.getTargetCount(), totalScore,
						m.getTargetScore());
			}
			}
			return Map.of("id", m.getMissionOrder(), "name", m.getMissionName(), "progress", progressText, "status",
					done ? "COMPLETED" : "IN_PROGRESS");
		}).toList();

		Map<String, Object> response = Map.of("userId", user.getId(), "username", user.getUsername(), "totalPoints",
				user.getTotalPoints(), "rewardClaimed", isClaimed, "daysRemaining", daysRemaining, "isExpired",
				isExpired, "missions", missionList);

		return ResponseEntity.ok(response);
	}
}
