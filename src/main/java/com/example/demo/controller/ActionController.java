package com.example.demo.controller;

import java.time.LocalDate;
import java.util.Map;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.dto.LaunchGameRequest;
import com.example.demo.dto.LoginRequest;
import com.example.demo.dto.PlayGameRequest;
import com.example.demo.entity.GamePlayRecord;
import com.example.demo.entity.User;
import com.example.demo.event.GameLaunchEvent;
import com.example.demo.event.GamePlayEvent;
import com.example.demo.event.UserLoginEvent;
import com.example.demo.repository.GamePlayRecordRepository;
import com.example.demo.repository.GameRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AuthService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ActionController {

	private final UserRepository userRepository;
	private final RocketMQTemplate rocketMQTemplate;
	private final StringRedisTemplate redisTemplate;
	private final GameRepository gameRepository;
	private final GamePlayRecordRepository gamePlayRecordRepository;
	private final AuthService authService;

	@PostMapping("/login")
	@Transactional
	public ResponseEntity<?> login(@RequestBody LoginRequest request) {
		String username = request.username();
		if (username == null || username.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Username cannot be empty"));
		}

		// 若使用者不存在，則自動新建一個使用者 (模擬自動註冊的便捷設計)
		User user = userRepository.findByUsername(username).orElseGet(() -> {
			log.info("Creating new user: {}", username);
			User newUser = User.builder().username(username).build();
			return userRepository.save(newUser);
		});

		// 建立並發送非同步 MQ 事件，用於任務系統結算
		UserLoginEvent event = new UserLoginEvent(user.getId(), LocalDate.now());
		rocketMQTemplate.convertAndSend("MISSION_TOPIC:LOGIN", event);

		// 產生 Token 並存入 Redis (設定 24 小時過期)
		String token = java.util.UUID.randomUUID().toString();
		redisTemplate.opsForValue().set("auth:token:" + token, String.valueOf(user.getId()),
				java.time.Duration.ofHours(24));

		log.info("User logged in. UserId: {}, Token: {}", user.getId(), token);

		return ResponseEntity.ok(Map.of("message", "Login successful", "userId", user.getId(), "username",
				user.getUsername(), "token", token));
	}

	@PostMapping("/launchGame")
	@Transactional
	public ResponseEntity<?> launchGame(@RequestHeader(value = "Authorization", required = false) String token,
			@RequestBody LaunchGameRequest request) {
		Long userId = authService.getUserIdFromToken(token);
		if (userId == null) {
			return ResponseEntity.status(401).body(Map.of("error", "Unauthorized: Invalid or missing token"));
		}

		Long gameId = request.gameId();
		if (gameId == null) {
			return ResponseEntity.badRequest().body(Map.of("error", "GameId is required"));
		}

		// 確認遊戲是否存在，依照正常流程，查無遊戲即回傳錯誤
		if (!gameRepository.existsById(gameId)) {
			return ResponseEntity.badRequest().body(Map.of("error", "Game not found"));
		}

		// 產生 Play Token (過期時間 30 分鐘)
		String playToken = java.util.UUID.randomUUID().toString();
		String sessionKey = "play:session:" + playToken;
		// 綁定 userId 與 gameId 放入 Redis
		redisTemplate.opsForValue().set(sessionKey, userId + ":" + gameId, java.time.Duration.ofMinutes(30));

		// 發送 MQ 事件
		GameLaunchEvent event = new GameLaunchEvent(userId, gameId, LocalDate.now());
		rocketMQTemplate.convertAndSend("MISSION_TOPIC:LAUNCH", event);

		log.info("User {} launched game {}, playToken: {}", userId, gameId, playToken);
		return ResponseEntity.ok(Map.of("message", "Game launched successfully", "playToken", playToken));
	}

	@PostMapping("/play")
	@Transactional
	public ResponseEntity<?> play(@RequestHeader(value = "Authorization", required = false) String token,
			@RequestBody PlayGameRequest request) {
		Long authUserId = authService.getUserIdFromToken(token);
		if (authUserId == null) {
			return ResponseEntity.status(401).body(Map.of("error", "Unauthorized: Invalid or missing auth token"));
		}

		String playToken = request.playToken();
		if (playToken == null || playToken.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "PlayToken is required"));
		}

		String sessionKey = "play:session:" + playToken;
		String sessionValue = redisTemplate.opsForValue().get(sessionKey);

		if (sessionValue == null) {
			return ResponseEntity.status(401).body(Map.of("error", "Unauthorized: Play session expired or invalid"));
		}

		// 解析 userId 與 gameId
		String[] parts = sessionValue.split(":");
		Long sessionUserId = Long.parseLong(parts[0]);
		Long gameId = Long.parseLong(parts[1]);

		// 確保打 /play 的人跟 launchGame 的人是同一個
		if (!authUserId.equals(sessionUserId)) {
			return ResponseEntity.status(403).body(Map.of("error", "Forbidden: Session owner mismatch"));
		}

		// 刷新 Redis 的過期時間 (再給 30 分鐘)
		redisTemplate.expire(sessionKey, java.time.Duration.ofMinutes(30));

		// 後端自動產生隨機分數 (這裡設定為 50 ~ 500 分)
		int score = new java.util.Random().nextInt(451) + 50;

		// 寫入遊玩紀錄
		GamePlayRecord record = GamePlayRecord.builder().userId(authUserId).gameId(gameId).score(score).build();
		gamePlayRecordRepository.save(record);

		// 發送 MQ 事件
		GamePlayEvent event = new GamePlayEvent(authUserId, gameId, score, LocalDate.now());
		rocketMQTemplate.convertAndSend("MISSION_TOPIC:PLAY", event);

		log.info("User {} played game {} with playToken {}, score {}", authUserId, gameId, playToken, score);
		return ResponseEntity.ok(Map.of("message", "Play recorded successfully", "score", score));
	}
}
