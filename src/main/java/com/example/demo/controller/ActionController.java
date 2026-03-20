package com.example.demo.controller;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.dto.LaunchGameRequest;
import com.example.demo.dto.LoginRequest;
import com.example.demo.dto.PlayGameRequest;
import com.example.demo.event.GameLaunchEvent;
import com.example.demo.event.GamePlayEvent;
import com.example.demo.event.UserLoginEvent;
import com.example.demo.model.GamePlayRecordModel;
import com.example.demo.model.UserModel;
import com.example.demo.repository.GamePlayRecordRepository;
import com.example.demo.repository.GameRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AuthService;
import com.example.demo.service.UserCacheService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Behavior Control", description = "APIs for handling user login, game launch, and play behavior, sending asynchronous MQ events")
public class ActionController {

	private final UserRepository userRepository;
	private final RocketMQTemplate rocketMQTemplate;
	private final StringRedisTemplate redisTemplate;
	private final GameRepository gameRepository;
	private final GamePlayRecordRepository gamePlayRecordRepository;
	private final AuthService authService;
	private final UserCacheService userCacheService;

	@PostMapping("/login")
	@Operation(summary = "User Login/Registration", description = "Simulates user login. Automatically creates a user if they don't exist, sends a LOGIN event upon success, and returns a token")
	public ResponseEntity<?> login(@RequestBody LoginRequest request) {
		String username = request.username();
		if (username == null || username.trim().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "Username cannot be empty"));
		}

		// Automatically create a new user if they don't exist
		UserModel user = userCacheService.getByUsername(username).orElseGet(() -> {
			log.info("Creating new user: {}", username);
			UserModel newUser = UserModel.builder().username(username).build();
			UserModel saved = userRepository.save(newUser);
			userCacheService.cacheUserObject(saved); // Cache the newly created user
			return saved;
		});

		// Create and send asynchronous MQ event for mission settlement
		UserLoginEvent event = new UserLoginEvent(user.getId(), LocalDate.now());
		rocketMQTemplate.convertAndSend("MISSION_TOPIC:LOGIN", event);

		// Generate Token and store in Redis (set to expire in 24 hours)
		String token = java.util.UUID.randomUUID().toString();
		redisTemplate.opsForValue().set("auth:token:" + token, String.valueOf(user.getId()),
				java.time.Duration.ofHours(24));

		log.info("User logged in. UserId: {}, Token: {}", user.getId(), token);

		return ResponseEntity.ok(Map.of("message", "Login successful", "userId", user.getId(), "username",
				user.getUsername(), "token", token));
	}

	@PostMapping("/launchGame")
	@Operation(summary = "Launch Game", description = "Requires Token. Verifies game existence, sends a LAUNCH event, and returns a playToken for gaming")
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

		// Check if game exists; return error if not found
		if (!gameRepository.existsById(gameId)) {
			return ResponseEntity.badRequest().body(Map.of("error", "Game not found"));
		}

		// Generate Play Token (expires in 30 minutes)
		String playToken = UUID.randomUUID().toString();
		String sessionKey = "play:session:" + playToken;
		// Bind userId and gameId in Redis
		redisTemplate.opsForValue().set(sessionKey, userId + ":" + gameId, java.time.Duration.ofMinutes(30));

		// 發送 MQ 事件
		GameLaunchEvent event = new GameLaunchEvent(userId, gameId, LocalDate.now());
		rocketMQTemplate.convertAndSend("MISSION_TOPIC:LAUNCH", event);

		log.info("User {} launched game {}, playToken: {}", userId, gameId, playToken);
		return ResponseEntity.ok(Map.of("message", "Game launched successfully", "playToken", playToken));
	}

	@PostMapping("/play")
	@Operation(summary = "Record Play Results", description = "Requires Token and PlayToken. Verifies token ownership, randomly generates score, and sends a PLAY event")
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

		// Parse userId and gameId
		String[] parts = sessionValue.split(":");
		Long sessionUserId = Long.parseLong(parts[0]);
		Long gameId = Long.parseLong(parts[1]);

		// Ensure the person calling /play is the same person who called launchGame
		if (!authUserId.equals(sessionUserId)) {
			return ResponseEntity.status(403).body(Map.of("error", "Forbidden: Session owner mismatch"));
		}

		// Refresh Redis expiration time (another 30 minutes)
		redisTemplate.expire(sessionKey, java.time.Duration.ofMinutes(30));

		// Backend automatically generates random score (here set to 50 ~ 500 points)
		int score = new java.util.Random().nextInt(451) + 50;

		// Write play record
		GamePlayRecordModel record = GamePlayRecordModel.builder().userId(authUserId).gameId(gameId).score(score)
				.build();
		gamePlayRecordRepository.save(record);

		// Send MQ event
		GamePlayEvent event = new GamePlayEvent(authUserId, gameId, score, LocalDate.now());
		rocketMQTemplate.convertAndSend("MISSION_TOPIC:PLAY", event);

		log.info("User {} played game {} with playToken {}, score {}", authUserId, gameId, playToken, score);
		return ResponseEntity.ok(Map.of("message", "Play recorded successfully", "score", score));
	}
}
