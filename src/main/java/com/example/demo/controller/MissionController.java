package com.example.demo.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.enums.ActivityKey;
import com.example.demo.service.AuthService;
import com.example.demo.service.MissionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Mission System", description = "Provides APIs for mission progress queries and reward claims")
public class MissionController {

	private final AuthService authService;
	private final MissionService missionService;

	@GetMapping("/missions")
	@Operation(summary = "Query mission progress", description = "Calculates a 30-day deadline based on the user's registration time, and dynamically retrieves task completion status from the database and Redis")
	public ResponseEntity<?> getMissions(@RequestHeader(value = "Authorization", required = false) String token) {
		Long userId = authService.getUserIdFromToken(token);
		if (userId == null) {
			return ResponseEntity.status(401).body(Map.of("error", "Unauthorized: Invalid or missing token"));
		}

		try {
			Map<String, Object> response = missionService.getUserMissionProgress(userId);
			return ResponseEntity.ok(response);
		} catch (IllegalStateException e) {
			return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
		} catch (Exception e) {
			return ResponseEntity.status(404).body(Map.of("error", "User not found"));
		}
	}

	@GetMapping("/admin/refresh-cache")
	@Operation(summary = "Refresh activity configuration cache", description = "Manually clear and reload the activity configuration cache in Redis")
	public ResponseEntity<?> refreshCache() {
		missionService.refreshActivityCache(ActivityKey.NEW_USER_MISSION.getKey());
		return ResponseEntity.ok(Map.of("message", "Activity cache refreshed successfully"));
	}
}
