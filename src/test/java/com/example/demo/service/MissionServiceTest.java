package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.example.demo.enums.ActivityKey;
import com.example.demo.enums.MissionType;
import com.example.demo.model.ActivityMasterModel;
import com.example.demo.model.ActivityMissionModel;
import com.example.demo.model.RewardRecordModel;
import com.example.demo.model.UserModel;
import com.example.demo.repository.ActivityMasterRepository;
import com.example.demo.repository.RewardRecordRepository;
import com.example.demo.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MissionServiceTest {

	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private UserRepository userRepository;
	@Mock
	private ActivityMasterRepository activityMasterRepository;
	@Mock
	private RewardRecordRepository rewardRecordRepository;
	@Mock
	private ObjectMapper objectMapper;
	@Mock
	private UserCacheService userCacheService;

	@Mock
	private ValueOperations<String, String> valueOperations;
	@Mock
	private HashOperations<String, Object, Object> hashOperations;
	@Mock
	private SetOperations<String, String> setOperations;

	@InjectMocks
	private MissionService missionService;

	private UserModel testUser;
	private ActivityMasterModel testActivity;

	@BeforeEach
	void setUp() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		testUser = UserModel.builder().id(1L).username("test_user").totalPoints(0)
				.createdAt(LocalDateTime.now().minusDays(10)) // 10天前註冊
				.build();

		ActivityMissionModel m1 = ActivityMissionModel.builder().id(1L).missionType(MissionType.LOGIN).missionName("M1")
				.targetCount(3).build();

		testActivity = ActivityMasterModel.builder().id(1L).activityKey("NEW_USER_MISSION")
				.activityName("New User Activity").daysLimit(30).totalReward(100).missions(List.of(m1)).build();
	}

	@Test
	void recordLogin_ShouldNotRecord_WhenExpired() {
		/*
		 * Expiration intercept test Purpose: Verify that the "30-day limit" is actually
		 * working. Scenario: Simulate a user who registered 31 days ago. Verify: When
		 * calling recordLogin, the system should recognize the user has expired and NOT
		 * perform any Redis write operations (verify(redisTemplate,
		 * never()).opsForHash()). This ensures expired users can no longer accumulate
		 * progress.
		 */
		// Mock user as expired (31 days ago)
		testUser.setCreatedAt(LocalDateTime.now().minusDays(31));

		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(anyString())).thenReturn(null);
		when(userCacheService.getUser(1L)).thenReturn(testUser);
		when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));

		missionService.recordLogin(1L, LocalDate.now());

		// Verify hash operations (recording logic) were NEVER called
		verify(redisTemplate, never()).opsForHash();
	}

	@Test
	void recordLogin_ShouldRecord_WhenNotExpired() {
		/*
		 * Progress accumulation test Purpose: Verify normal progress accumulation.
		 * Scenario: Simulate a user who registered 10 days ago and has no login record
		 * in Redis. Verify: The system should normally write the new login days to
		 * Redis (login_days = 1). Confirm that opsForSet().size() is mocked to avoid
		 * NPE during mission completion check.
		 */
		// Mock expiry check
		when(valueOperations.get("mission:user:1:expired")).thenReturn("false");
		when(valueOperations.get("config:activity:NEW_USER_MISSION")).thenReturn(null);
		when(redisTemplate.opsForHash()).thenReturn(hashOperations);
		when(hashOperations.get(anyString(), eq("last_login_date"))).thenReturn(null);
		when(hashOperations.get(anyString(), eq("login_days"))).thenReturn("0");
		when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));
		when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L)).thenReturn(Optional.empty());
		when(redisTemplate.opsForSet()).thenReturn(setOperations);
		when(setOperations.size(anyString())).thenReturn(0L);
		missionService.recordLogin(1L, LocalDate.now());
		verify(hashOperations).put(anyString(), eq("login_days"), eq("1"));
	}

	@Test
	void recordLaunch_ShouldRecordDistinctGames() {
		/*
		 * Distinct game launch test Purpose: Verify that launching a game is correctly
		 * recorded in a Redis Set. Verify: The system should call opsForSet().add() and
		 * then check all missions.
		 */
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisTemplate.opsForSet()).thenReturn(setOperations);
		// Mock requirements and reward check
		when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));
		when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L)).thenReturn(Optional.empty());
		when(redisTemplate.opsForHash()).thenReturn(hashOperations);
		when(hashOperations.get(anyString(), anyString())).thenReturn(null);
		when(userCacheService.getUser(1L)).thenReturn(testUser);

		missionService.recordLaunch(1L, 101L);

		verify(setOperations).add(anyString(), eq("101"));
		verify(setOperations, times(2)).size(anyString());
	}

	@Test
	void recordPlay_ShouldAccumulateSessionsAndScore() {
		/*
		 * Play accumulation test Purpose: Verify that play sessions and scores are
		 * incremented in Redis. Verify: The system should call opsForHash().increment()
		 * for both play_sessions and total_score.
		 */
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisTemplate.opsForHash()).thenReturn(hashOperations);
		when(valueOperations.get("mission:user:1:expired")).thenReturn("false");

		// Mock requirements and reward check
		when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));
		when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L)).thenReturn(Optional.empty());
		when(hashOperations.get(anyString(), anyString())).thenReturn(null);
		when(redisTemplate.opsForSet()).thenReturn(setOperations);

		missionService.recordPlay(1L, 101L, 500);

		verify(hashOperations).increment(anyString(), eq("play_sessions"), eq(1L));
		verify(hashOperations).increment(anyString(), eq("total_score"), eq(500L));
	}

	@Test
	void checkAllMissionsAndReward_ShouldAwardPoints_WhenMissionsDone() {
		/*
		 * Reward and persistence test Purpose: This is the most critical test,
		 * verifying dynamic target judgment and reward persistence. Scenario: 1. Fetch
		 * mission requirements from ActivityMaster (e.g., 3 days of login required). 2.
		 * Simulate that Redis progress has met the target (login_days = 3). 3. Simulate
		 * that both DB and Redis show "not claimed". Verify: Point Update: The user's
		 * totalPoints should increase by 100. DB Persistence:
		 * rewardRecordRepository.save() must be called to write to the record table
		 * (most important step). Redis Sync: Redis reward flag should be set to "1".
		 */
		// Mocking requirements
		when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));
		when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L)).thenReturn(Optional.empty());
		when(redisTemplate.opsForHash()).thenReturn(hashOperations);
		when(hashOperations.get(anyString(), eq("reward_claimed"))).thenReturn(null);

		// Mock opsForSet to avoid NPE
		when(redisTemplate.opsForSet()).thenReturn(setOperations);
		when(setOperations.size(anyString())).thenReturn(0L);

		// Mock progress: login_days = 3 (target is 3)
		when(hashOperations.get(anyString(), eq("login_days"))).thenReturn("3");
		when(hashOperations.get(anyString(), eq("play_sessions"))).thenReturn("0");
		when(hashOperations.get(anyString(), eq("total_score"))).thenReturn("0");

		when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

		missionService.checkAllMissionsAndReward(1L, ActivityKey.NEW_USER_MISSION);

		// Verify user points updated
		assertThat(testUser.getTotalPoints()).isEqualTo(100);
		verify(userRepository).save(testUser);
		verify(userCacheService).evictUser(1L);

		// Verify reward record persisted
		verify(rewardRecordRepository).save(any(RewardRecordModel.class));

		// Verify Redis marked
		verify(hashOperations).put(anyString(), eq("reward_claimed"), eq("1"));
	}

	    @Test
    void checkAllMissionsAndReward_ShouldReturnEarly_WhenAlreadyClaimedInDb() {
    	/*
    	 *  Double-claim prevention test
    	 *	Purpose: Verify the reliability of the database as the "Source of Truth".
    	 *	Scenario: Even if there's no reward record in Redis (simulating data loss or malicious clear), as long as there's a record for the user in the reward_records table.
    	 *	Verify: The system should detect this immediately and Return Early, NOT calling userRepository.save() again to update points. This fundamentally prevents duplicate claims.
    	 */
        when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));
        when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L))
                .thenReturn(Optional.of(new RewardRecordModel()));

        missionService.checkAllMissionsAndReward(1L, ActivityKey.NEW_USER_MISSION);

        // Verify points were NOT awarded
        verify(userRepository, never()).save(any(UserModel.class));
    }

    @Test
    void recordLogin_ShouldNotDuplicate_WhenSameDayLogin() {
        /*
         *  Same-day login duplicate prevention test
         *  Purpose: Verify that logging in twice on the same day does NOT increment login_days.
         *  Scenario: User already logged in today (last_login_date = today, login_days = 2).
         *  Verify: login_days should remain 2, not become 3.
         */
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("mission:user:1:expired")).thenReturn("false");
        when(valueOperations.get("config:activity:NEW_USER_MISSION")).thenReturn(null);

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(anyString(), eq("last_login_date")))
                .thenReturn(LocalDate.now().toString()); // Already logged in today
        when(hashOperations.get(anyString(), eq("login_days"))).thenReturn("2");

        when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));
        when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L)).thenReturn(Optional.empty());

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.size(anyString())).thenReturn(0L);

        missionService.recordLogin(1L, LocalDate.now());

        // login_days should stay at 2, not increase
        verify(hashOperations).put(anyString(), eq("login_days"), eq("2"));
    }

    @Test
    void recordLogin_ShouldReset_WhenLoginGapMoreThanOneDay() {
        /*
         *  Login streak reset test
         *  Purpose: Verify that a login gap of more than 1 day resets the consecutive day count.
         *  Scenario: User last logged in 3 days ago (login_days = 5), logs in today.
         *  Verify: login_days should reset to 1.
         */
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("mission:user:1:expired")).thenReturn("false");
        when(valueOperations.get("config:activity:NEW_USER_MISSION")).thenReturn(null);

        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(anyString(), eq("last_login_date")))
                .thenReturn(LocalDate.now().minusDays(3).toString()); // Last login 3 days ago
        when(hashOperations.get(anyString(), eq("login_days"))).thenReturn("5");

        when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));
        when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L)).thenReturn(Optional.empty());

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.size(anyString())).thenReturn(0L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

        missionService.recordLogin(1L, LocalDate.now());

        // login_days should reset to 1
        verify(hashOperations).put(anyString(), eq("login_days"), eq("1"));
    }

    @Test
    void checkAllMissionsAndReward_ShouldNotAward_WhenRedisAlreadyClaimed() {
        /*
         *  Redis fast-filter test
         *  Purpose: Verify that the Redis reward_claimed flag acts as a fast-return guard.
         *  Scenario: DB has no record, but Redis already shows reward_claimed = "1".
         *  Verify: No points are awarded and userRepository.save() is never called.
         */
        when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));
        when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L)).thenReturn(Optional.empty());
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(anyString(), eq("reward_claimed"))).thenReturn("1"); // Already claimed in Redis

        missionService.checkAllMissionsAndReward(1L, ActivityKey.NEW_USER_MISSION);

        verify(userRepository, never()).save(any(UserModel.class));
    }

    @Test
    void checkAllMissionsAndReward_ShouldNotAward_WhenMissionsNotDone() {
        /*
         *  Insufficient progress test
         *  Purpose: Verify that rewards are NOT given when mission targets are not met.
         *  Scenario: login_days = 1, but target is 3. All other missions also incomplete.
         *  Verify: No points are awarded and userRepository.save() is never called.
         */
        when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));
        when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L)).thenReturn(Optional.empty());
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(anyString(), eq("reward_claimed"))).thenReturn(null);

        // Progress not met: login_days = 1, target = 3
        when(hashOperations.get(anyString(), eq("login_days"))).thenReturn("1");
        when(hashOperations.get(anyString(), eq("play_sessions"))).thenReturn("0");
        when(hashOperations.get(anyString(), eq("total_score"))).thenReturn("0");

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.size(anyString())).thenReturn(0L);

        missionService.checkAllMissionsAndReward(1L, ActivityKey.NEW_USER_MISSION);

        verify(userRepository, never()).save(any(UserModel.class));
    }
}
