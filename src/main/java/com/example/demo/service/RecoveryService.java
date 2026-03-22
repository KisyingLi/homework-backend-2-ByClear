package com.example.demo.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.enums.ActivityKey;
import com.example.demo.helper.RedisHelper;
import com.example.demo.model.ActivityMasterModel;
import com.example.demo.model.GameLaunchRecordModel;
import com.example.demo.model.GamePlayRecordModel;
import com.example.demo.model.RewardRecordModel;
import com.example.demo.model.UserLoginRecordModel;
import com.example.demo.model.UserModel;
import com.example.demo.repository.GameLaunchRecordRepository;
import com.example.demo.repository.GamePlayRecordRepository;
import com.example.demo.repository.RewardRecordRepository;
import com.example.demo.repository.UserLoginRecordRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final UserLoginRecordRepository userLoginRecordRepository;
    private final GameLaunchRecordRepository gameLaunchRecordRepository;
    private final GamePlayRecordRepository gamePlayRecordRepository;
    private final RewardRecordRepository rewardRecordRepository;
    private final MissionService missionService;
    private final UserCacheService userCacheService;
    private final RedisHelper redis;

    private static final ActivityKey DEFAULT_ACTIVITY = ActivityKey.NEW_USER_MISSION;

    @Transactional(readOnly = true)
    public void recoverUserMissionProgress(Long userId) {
        log.info("Starting data recovery for user {}", userId);

        UserModel user = userCacheService.getUser(userId);

        String key = redis.missionUserKey(userId, DEFAULT_ACTIVITY);

        // 1. Recover Login Days
        List<UserLoginRecordModel> loginRecords = userLoginRecordRepository.findByUserIdOrderByLoginDateDesc(userId);
        int consecutiveDays = 0;
        LocalDate lastLoginDate = null;
        
        if (!loginRecords.isEmpty()) {
            lastLoginDate = loginRecords.get(0).getLoginDate();
            consecutiveDays = 1;
            LocalDate currentCheckDate = lastLoginDate;

            for (int i = 1; i < loginRecords.size(); i++) {
                LocalDate previousDate = loginRecords.get(i).getLoginDate();
                long daysBetween = ChronoUnit.DAYS.between(previousDate, currentCheckDate);
                if (daysBetween == 1) {
                    consecutiveDays++;
                    currentCheckDate = previousDate;
                } else if (daysBetween == 0) {
                    // Same day login, ignore
                } else {
                    break; // Streak broken
                }
            }
        }
        
        if (lastLoginDate != null) {
            redis.hashPut(key, "last_login_date", lastLoginDate.toString());
        }
        redis.hashPut(key, "login_days", String.valueOf(consecutiveDays));
        
        // 2. Recover Launched Games
        List<GameLaunchRecordModel> launchRecords = gameLaunchRecordRepository.findByUserId(userId);
        String launchedKey = redis.launchedGamesKey(userId, DEFAULT_ACTIVITY);
        // Clear existing set first
        redis.delete(launchedKey);
        for (GameLaunchRecordModel record : launchRecords) {
            redis.setAdd(launchedKey, String.valueOf(record.getGameId()));
        }

        // 3. Recover Play Sessions and Total Score
        List<GamePlayRecordModel> playRecords = gamePlayRecordRepository.findByUserId(userId);
        long totalSessions = playRecords.size();
        long totalScore = playRecords.stream().mapToLong(GamePlayRecordModel::getScore).sum();

        redis.hashPut(key, "play_sessions", String.valueOf(totalSessions));
        redis.hashPut(key, "total_score", String.valueOf(totalScore));

        // 4. Recover Reward Claimed Flag
        ActivityMasterModel activity = missionService.getActivityConfig(DEFAULT_ACTIVITY.getKey());
        Optional<RewardRecordModel> rewardRecord = rewardRecordRepository.findByUserIdAndActivityId(userId, activity.getId());
        if (rewardRecord.isPresent()) {
            redis.hashPut(key, "reward_claimed", "1");
        }

        // 5. Calculate and set TTL
        LocalDateTime expirationTime = user.getCreatedAt().plusDays(activity.getDaysLimit());
        Duration ttl = Duration.between(LocalDateTime.now(), expirationTime);
        if (!ttl.isNegative() && !ttl.isZero()) {
            redis.expire(key, ttl);
            redis.expire(launchedKey, ttl);
        } else {
            redis.expire(key, Duration.ofDays(7));
            redis.expire(launchedKey, Duration.ofDays(7));
        }

        log.info("Finished data recovery for user {}. ConsecutiveDays: {}, DistinctGames: {}, Sessions: {}, Score: {}", 
            userId, consecutiveDays, redis.setSize(launchedKey), totalSessions, totalScore);
    }
}
