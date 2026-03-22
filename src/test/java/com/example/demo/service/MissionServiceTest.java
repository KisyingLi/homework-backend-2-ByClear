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
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.demo.enums.ActivityKey;
import com.example.demo.enums.MissionType;
import com.example.demo.helper.RedisHelper;
import com.example.demo.model.ActivityMasterModel;
import com.example.demo.model.ActivityMissionModel;
import com.example.demo.model.RewardRecordModel;
import com.example.demo.model.UserModel;
import com.example.demo.repository.ActivityMasterRepository;
import com.example.demo.repository.GameLaunchRecordRepository;
import com.example.demo.repository.RewardRecordRepository;
import com.example.demo.repository.UserLoginRecordRepository;
import com.example.demo.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MissionServiceTest {

	@Mock
	private RedisHelper redis;
	@Mock
	private UserRepository userRepository;
	@Mock
	private ActivityMasterRepository activityMasterRepository;
	@Mock
	private RewardRecordRepository rewardRecordRepository;
	@Mock
	private UserLoginRecordRepository userLoginRecordRepository;
	@Mock
	private GameLaunchRecordRepository gameLaunchRecordRepository;
	@Mock
	private ObjectMapper objectMapper;
	@Mock
	private UserCacheService userCacheService;

	@InjectMocks
	private MissionService missionService;

	private UserModel testUser;
	private ActivityMasterModel testActivity;

	@BeforeEach
	void setUp() {
		testUser = UserModel.builder().id(1L).username("test_user").totalPoints(0)
				.createdAt(LocalDateTime.now().minusDays(10)) // 10天前註冊
				.build();

		ActivityMissionModel m1 = ActivityMissionModel.builder().id(1L).missionType(MissionType.LOGIN).missionName("M1")
				.targetCount(3).build();

		testActivity = ActivityMasterModel.builder().id(1L).activityKey("NEW_USER_MISSION")
				.activityName("New User Activity").daysLimit(30).totalReward(100).missions(List.of(m1)).build();
                
		// Set basic key mappings for RedisHelper to avoid NPEs if not explicitly stubbed
		Mockito.lenient().when(redis.missionUserKey(any(Long.class), any(ActivityKey.class))).thenAnswer(i -> "mission:user:" + i.getArgument(0) + ":" + ((ActivityKey)i.getArgument(1)).getKey());
		Mockito.lenient().when(redis.missionUserKey(any(Long.class), any(String.class))).thenAnswer(i -> "mission:user:" + i.getArgument(0) + ":" + i.getArgument(1));
		Mockito.lenient().when(redis.activityConfigKey(anyString())).thenAnswer(i -> "config:activity:" + i.getArgument(0));
		Mockito.lenient().when(redis.launchedGamesKey(any(Long.class), any(ActivityKey.class))).thenAnswer(i -> "mission:user:" + i.getArgument(0) + ":" + ((ActivityKey)i.getArgument(1)).getKey() + ":launched_games");
		Mockito.lenient().when(redis.launchedGamesKey(any(Long.class), any(String.class))).thenAnswer(i -> "mission:user:" + i.getArgument(0) + ":" + i.getArgument(1) + ":launched_games");
	}

	@Test
	void recordLogin_ShouldNotRecord_WhenExpired() {
		// Mock user as expired (31 days ago)
		testUser.setCreatedAt(LocalDateTime.now().minusDays(31));

		when(redis.hashGet(anyString(), eq("expired"))).thenReturn(null);
		when(userCacheService.getUser(1L)).thenReturn(testUser);
		when(redis.getAsJson(anyString(), eq(ActivityMasterModel.class))).thenReturn(null);
		when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));

		missionService.recordLogin(1L, LocalDate.now());

		// Verify hash operations (recording logic) were NEVER called
		verify(redis, never()).hashPut(anyString(), eq("last_login_date"), anyString());
	}

	@Test
	void recordLogin_ShouldRecord_WhenNotExpired() {
		// Mock expiry check
		when(redis.hashGet(anyString(), eq("expired"))).thenReturn("false");
		
		// return empty Map to avoid NPE in checkAllMissionsAndReward
		when(redis.hashGetAll(anyString())).thenReturn(Map.of("login_days", "1"));
		when(redis.setSize(anyString())).thenReturn(0L);
		when(redis.getAsJson(anyString(), eq(ActivityMasterModel.class))).thenReturn(testActivity);

		when(redis.hashGet(anyString(), eq("last_login_date"))).thenReturn(null);
		when(redis.hashGet(anyString(), eq("login_days"))).thenReturn("0");
		
		when(userLoginRecordRepository.findByUserIdAndLoginDate(eq(1L), any(LocalDate.class))).thenReturn(Optional.empty());

		missionService.recordLogin(1L, LocalDate.now());
		verify(redis).hashPut(anyString(), eq("login_days"), eq("1"));
	}

	@Test
	void recordLaunch_ShouldRecordDistinctGames() {
		when(redis.hashGet(anyString(), eq("expired"))).thenReturn("false");
		
		when(redis.setSize(anyString())).thenReturn(1L);
		when(userCacheService.getUser(1L)).thenReturn(testUser);
		when(redis.getAsJson(anyString(), eq(ActivityMasterModel.class))).thenReturn(testActivity);
		
		when(redis.hashGetAll(anyString())).thenReturn(Map.of());

		missionService.recordLaunch(1L, 101L);

		verify(redis).setAdd(anyString(), eq("101"));
		verify(redis, times(2)).setSize(anyString());
	}

	@Test
	void recordPlay_ShouldAccumulateSessionsAndScore() {
		when(redis.hashGet(anyString(), eq("expired"))).thenReturn("false");

		// Mock requirements and reward check
		when(redis.getAsJson(anyString(), eq(ActivityMasterModel.class))).thenReturn(testActivity);
		when(redis.hashGetAll(anyString())).thenReturn(Map.of());
		when(redis.setSize(anyString())).thenReturn(0L);

		missionService.recordPlay(1L, 101L, 500);

		verify(redis).hashIncrement(anyString(), eq("play_sessions"), eq(1L));
		verify(redis).hashIncrement(anyString(), eq("total_score"), eq(500L));
	}

	@Test
	void checkAllMissionsAndReward_ShouldAwardPoints_WhenMissionsDone() {
		when(redis.getAsJson(anyString(), eq(ActivityMasterModel.class))).thenReturn(testActivity);
		when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L)).thenReturn(Optional.empty());

		// Mock progress: login_days = 3 (target is 3)
		when(redis.hashGetAll(anyString())).thenReturn(Map.of(
			"login_days", "3",
			"play_sessions", "0",
			"total_score", "0"
		));
		
		when(redis.setSize(anyString())).thenReturn(0L);

		when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

		missionService.checkAllMissionsAndReward(1L, ActivityKey.NEW_USER_MISSION);

		// Verify user points updated
		assertThat(testUser.getTotalPoints()).isEqualTo(100);
		verify(userRepository).save(testUser);
		verify(userCacheService).evictUser(1L);

		// Verify reward record persisted
		verify(rewardRecordRepository).save(any(RewardRecordModel.class));

		// Verify Redis marked
		verify(redis).hashPut(anyString(), eq("reward_claimed"), eq("1"));
	}

	@Test
	void checkAllMissionsAndReward_ShouldReturnEarly_WhenAlreadyClaimedInDb() {
		when(redis.getAsJson(anyString(), eq(ActivityMasterModel.class))).thenReturn(testActivity);
		
		when(redis.hashGetAll(anyString())).thenReturn(Map.of(
			"login_days", "3"
		));
		when(redis.setSize(anyString())).thenReturn(0L);
		// DB says already claimed
		when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L)).thenReturn(Optional.of(new RewardRecordModel()));

		missionService.checkAllMissionsAndReward(1L, ActivityKey.NEW_USER_MISSION);

		// Verify points were NOT awarded
		verify(userRepository, never()).save(any(UserModel.class));
	}

	@Test
	void recordLogin_ShouldNotDuplicate_WhenSameDayLogin() {
		when(redis.hashGet(anyString(), eq("expired"))).thenReturn("false");
		
		when(redis.getAsJson(anyString(), eq(ActivityMasterModel.class))).thenReturn(testActivity);

		when(redis.hashGet(anyString(), eq("last_login_date")))
				.thenReturn(LocalDate.now().toString()); // Already logged in today
		when(redis.hashGet(anyString(), eq("login_days"))).thenReturn("2");

		when(redis.setSize(anyString())).thenReturn(0L);
		when(redis.hashGetAll(anyString())).thenReturn(Map.of("login_days", "2"));

		missionService.recordLogin(1L, LocalDate.now());

		// login_days should stay at 2, not increase
		verify(redis).hashPut(anyString(), eq("login_days"), eq("2"));
	}

	@Test
	void recordLogin_ShouldReset_WhenLoginGapMoreThanOneDay() {
		when(redis.hashGet(anyString(), eq("expired"))).thenReturn("false");
		when(redis.getAsJson(anyString(), eq(ActivityMasterModel.class))).thenReturn(testActivity);

		when(redis.hashGet(anyString(), eq("last_login_date")))
				.thenReturn(LocalDate.now().minusDays(3).toString()); // Last login 3 days ago
		when(redis.hashGet(anyString(), eq("login_days"))).thenReturn("5");

		when(redis.setSize(anyString())).thenReturn(0L);
		when(redis.hashGetAll(anyString())).thenReturn(Map.of("login_days", "1"));

		missionService.recordLogin(1L, LocalDate.now());

		// login_days should reset to 1
		verify(redis).hashPut(anyString(), eq("login_days"), eq("1"));
	}

	@Test
	void checkAllMissionsAndReward_ShouldNotAward_WhenRedisAlreadyClaimed() {
		when(redis.getAsJson(anyString(), eq(ActivityMasterModel.class))).thenReturn(testActivity);
		
		when(redis.hashGetAll(anyString())).thenReturn(Map.of(
			"reward_claimed", "1"
		));

		missionService.checkAllMissionsAndReward(1L, ActivityKey.NEW_USER_MISSION);

		verify(userRepository, never()).save(any(UserModel.class));
	}

	@Test
	void checkAllMissionsAndReward_ShouldNotAward_WhenMissionsNotDone() {
		when(redis.getAsJson(anyString(), eq(ActivityMasterModel.class))).thenReturn(testActivity);
		
		// Progress not met: login_days = 1, target = 3
		when(redis.hashGetAll(anyString())).thenReturn(Map.of(
			"login_days", "1",
			"play_sessions", "0",
			"total_score", "0"
		));
		
		when(redis.setSize(anyString())).thenReturn(0L);

		missionService.checkAllMissionsAndReward(1L, ActivityKey.NEW_USER_MISSION);

		verify(userRepository, never()).save(any(UserModel.class));
	}
}
