package com.example.demo.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.SetOperations;

import com.example.demo.entity.ActivityMaster;
import com.example.demo.entity.ActivityMission;
import com.example.demo.entity.RewardRecord;
import com.example.demo.entity.User;
import com.example.demo.repository.ActivityMasterRepository;
import com.example.demo.repository.RewardRecordRepository;
import com.example.demo.repository.UserRepository;

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
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private SetOperations<String, String> setOperations;

    @InjectMocks
    private MissionService missionService;

    private User testUser;
    private ActivityMaster testActivity;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(1L)
                .username("test_user")
                .totalPoints(0)
                .createdAt(LocalDateTime.now().minusDays(10)) // 10天前註冊
                .build();

        ActivityMission m1 = ActivityMission.builder().id(1L).missionOrder(1).missionName("M1").targetCount(3).build();
        
        testActivity = ActivityMaster.builder()
                .id(1L)
                .activityKey("NEW_USER_MISSION")
                .activityName("New User Activity")
                .daysLimit(30)
                .totalReward(100)
                .missions(List.of(m1))
                .build();
    }

    @Test
    void recordLogin_ShouldNotRecord_WhenExpired() {
    	/*
    	 *  過期攔截測試
    	 *	目的：驗證「30 天期限」是否真的有在起作用。
    	 *	情境：模擬一個註冊於 31 天前的用戶。
    	 *	驗證點：當呼叫 recordLogin時，系統應能識別用戶已過期，並完全不執行 Redis 的寫入動作 (verify(redisTemplate, never()).opsForHash())。
    	 	這保證了過期用戶無法再累積進度。
    	 */
        // Mock user as expired (31 days ago)
        testUser.setCreatedAt(LocalDateTime.now().minusDays(31));
        
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));
        when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));

        missionService.recordLogin(1L, LocalDate.now());

        // Verify hash operations (recording logic) were NEVER called
        verify(redisTemplate, never()).opsForHash();
    }

    @Test
    void recordLogin_ShouldRecord_WhenNotExpired() {
    	/*
    	 *  過期攔截測試
    	 *	目的：驗證正常狀態下的進度累積。
    	 *	情境：模擬註冊 10 天的用戶，且 Redis 中尚未有登入紀錄。
    	 *	驗證點：系統應該正常向 Redis 寫入新的登入天數 (login_days = 1)。
    	 	確認 Mock 了 opsForSet().size() 以避免在檢查任務達標時出現空指標異常。
    	 */
        // Mock expiry check
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("false"); // Mock cached not expired
        
        // Mock progress recording
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(anyString(), eq("last_login_date"))).thenReturn(null);
        when(hashOperations.get(anyString(), eq("login_days"))).thenReturn("0");

        // Mock checkReward logic part
        when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));
        when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L)).thenReturn(Optional.empty());
        
        // Fix NPE by mocking opsForSet().size()
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.size(anyString())).thenReturn(0L);

        missionService.recordLogin(1L, LocalDate.now());

        verify(hashOperations).put(anyString(), eq("login_days"), eq("1"));
    }

    @Test
    void checkAllMissionsAndReward_ShouldAwardPoints_WhenMissionsDone() {
    	/*
    	 *  發獎與持久化測試
    	 *	目的：這是最關鍵的測試，驗證動態達標判斷與領獎持久化。
    	 *	情境：
    	 	1. 從 ActivityMaster 抓取任務要求（例如：登入需 3 天）。
			2. 模擬 Redis 中的進度已達標（login_days = 3）。
			3. 模擬資料庫與 Redis 均顯示「尚未領獎」。
    	 *	驗證點：
			分數更新：用戶的 totalPoints 應增加 100 分。
			資料庫持久化：必須呼叫 rewardRecordRepository.save() 寫入紀錄表（最重要的一步）。
			Redis 同步：Redis 的領標標記應設為 "1"。
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

        missionService.checkAllMissionsAndReward(1L);

        // Verify user points updated
        assertThat(testUser.getTotalPoints()).isEqualTo(100);
        verify(userRepository).save(testUser);
        
        // Verify reward record persisted
        verify(rewardRecordRepository).save(any(RewardRecord.class));
        
        // Verify Redis marked
        verify(hashOperations).put(anyString(), eq("reward_claimed"), eq("1"));
    }

    @Test
    void checkAllMissionsAndReward_ShouldReturnEarly_WhenAlreadyClaimedInDb() {
        when(activityMasterRepository.findByActivityKey("NEW_USER_MISSION")).thenReturn(Optional.of(testActivity));
        when(rewardRecordRepository.findByUserIdAndActivityId(1L, 1L))
                .thenReturn(Optional.of(new RewardRecord()));

        missionService.checkAllMissionsAndReward(1L);

        // Verify points were NOT awarded
        verify(userRepository, never()).save(any(User.class));
    }
}
