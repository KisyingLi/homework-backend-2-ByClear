package com.example.demo.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.RecoveryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/admin/recovery")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Data Recovery", description = "Admin APIs for recovering mission data from DB to Redis")
public class RecoveryController {

    private final RecoveryService recoveryService;

    @PostMapping("/user/{userId}")
    @Operation(summary = "Recover user mission progress", description = "Rebuilds a specific user's mission progress in Redis using DB records")
    public ResponseEntity<?> recoverUserProgress(@PathVariable Long userId) {
        log.info("Received request to recover mission progress for user {}", userId);
        
        try {
            recoveryService.recoverUserMissionProgress(userId);
            return ResponseEntity.ok(Map.of(
                "message", "Data recovery successful for user " + userId,
                "userId", userId
            ));
        } catch (Exception e) {
            log.error("Failed to recover user progress", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Recovery failed: " + e.getMessage()
            ));
        }
    }
}
