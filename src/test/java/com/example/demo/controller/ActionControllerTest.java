package com.example.demo.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Optional;
import java.util.UUID;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.dto.LaunchGameRequest;
import com.example.demo.dto.LoginRequest;
import com.example.demo.dto.PlayGameRequest;
import com.example.demo.event.GameLaunchEvent;
import com.example.demo.event.GamePlayEvent;
import com.example.demo.event.UserLoginEvent;
import com.example.demo.model.UserModel;
import com.example.demo.repository.GamePlayRecordRepository;
import com.example.demo.repository.GameRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AuthService;
import com.example.demo.service.UserCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(ActionController.class)
class ActionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RocketMQTemplate rocketMQTemplate;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private GameRepository gameRepository;

    @MockitoBean
    private GamePlayRecordRepository gamePlayRecordRepository;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserCacheService userCacheService;

    @MockitoBean
    private ValueOperations<String, String> valueOperations;

    private UserModel testUser;

    @BeforeEach
    void setUp() {
        testUser = UserModel.builder()
                .id(1L)
                .username("testuser")
                .build();
    }

    @Test
    void login_ShouldCreateUserAndReturnToken_WhenUserDoesNotExist() throws Exception {
        /*
         *  New user registration test
         *  Purpose: Verify that a brand-new user is automatically registered on first login.
         *  Scenario: userCacheService returns empty (user not found in cache or DB).
         *  Verify: userRepository.save() is called once to create the new account,
         *          response returns 200 with userId and token,
         *          and a UserLoginEvent is sent via RocketMQ.
         */
        LoginRequest request = new LoginRequest("testuser");
        
        when(userCacheService.getByUsername("testuser")).thenReturn(Optional.empty());
        when(userRepository.save(any(UserModel.class))).thenReturn(testUser);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        mockMvc.perform(post("/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.token").exists());

        verify(userRepository).save(any(UserModel.class));
        verify(userCacheService).cacheUserObject(any(UserModel.class));
		verify(rocketMQTemplate).convertAndSend(eq("MISSION_TOPIC:LOGIN"), any(UserLoginEvent.class));
    }

    @Test
    void launchGame_ShouldReturnPlayToken_WhenTokenIsValid() throws Exception {
        /*
         *  Normal game launch test
         *  Purpose: Verify that a valid token and existing game ID returns a playToken.
         *  Scenario: Token is valid (authService returns userId = 1), game ID 1 exists.
         *  Verify: Response returns 200 with a playToken,
         *          and a GameLaunchEvent is sent via RocketMQ.
         */
        String token = UUID.randomUUID().toString();
        LaunchGameRequest request = new LaunchGameRequest(1L);

        when(authService.getUserIdFromToken(token)).thenReturn(1L);
        when(gameRepository.existsById(1L)).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        mockMvc.perform(post("/api/launchGame")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playToken").exists());

		verify(rocketMQTemplate).convertAndSend(eq("MISSION_TOPIC:LAUNCH"), any(GameLaunchEvent.class));
    }

    @Test
    void play_ShouldReturnScore_WhenSessionIsValid() throws Exception {
        /*
         *  Normal play session test
         *  Purpose: Verify that a valid playToken results in a score being recorded.
         *  Scenario: Token is valid, Redis contains the play session (userId:gameId = "1:1").
         *  Verify: Response returns 200 with a score,
         *          gamePlayRecordRepository.save() is called to persist the result,
         *          and a GamePlayEvent is sent via RocketMQ.
         */
        String token = "auth-token";
        String playToken = "play-token";
        PlayGameRequest request = new PlayGameRequest(playToken);

        when(authService.getUserIdFromToken(token)).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("play:session:" + playToken)).thenReturn("1:1"); // userId:gameId

        mockMvc.perform(post("/api/play")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").exists());

        verify(gamePlayRecordRepository).save(any());
        verify(rocketMQTemplate).convertAndSend(eq("MISSION_TOPIC:PLAY"), any(GamePlayEvent.class));
    }

    @Test
    void play_ShouldReturnForbidden_WhenSessionOwnerMismatch() throws Exception {
        /*
         *  Session hijack prevention test
         *  Purpose: Verify that a user cannot use another user's playToken.
         *  Scenario: Attacker (userId = 2) tries to submit a play result using a playToken
         *            that belongs to victim (userId = 1), Redis shows "1:1" as the session owner.
         *  Verify: Response status is 403 Forbidden, preventing unauthorized score submission.
         */
        String token = "attacker-token";
        String playToken = "victim-play-token";
        PlayGameRequest request = new PlayGameRequest(playToken);

        when(authService.getUserIdFromToken(token)).thenReturn(2L); // Attacker's ID
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("play:session:" + playToken)).thenReturn("1:1"); // Original owner ID is 1

        mockMvc.perform(post("/api/play")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void login_ShouldReturnToken_WhenUserAlreadyExists() throws Exception {
        /*
         *  Existing user login test
         *  Purpose: Verify that if the user already exists in cache, no new account is created.
         *  Scenario: userCacheService returns an existing user.
         *  Verify: userRepository.save() is NEVER called, response returns 200 with userId and token.
         */
        LoginRequest request = new LoginRequest("testuser");

        when(userCacheService.getByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        mockMvc.perform(post("/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.token").exists());

        // Existing user should NOT create a new record
        verify(userRepository, never()).save(any(UserModel.class));
    }

    @Test
    void launchGame_ShouldReturnBadRequest_WhenGameNotExist() throws Exception {
        /*
         *  Game not found test
         *  Purpose: Verify that launching a non-existent game returns 400 Bad Request.
         *  Scenario: gameRepository.existsById returns false.
         *  Verify: Response status is 400 with an error message.
         */
        String token = UUID.randomUUID().toString();
        LaunchGameRequest request = new LaunchGameRequest(999L); // Non-existent game ID

        when(authService.getUserIdFromToken(token)).thenReturn(1L);
        when(gameRepository.existsById(999L)).thenReturn(false);

        mockMvc.perform(post("/api/launchGame")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Game not found"));
    }

    @Test
    void launchGame_ShouldReturnUnauthorized_WhenTokenInvalid() throws Exception {
        /*
         *  Invalid token test
         *  Purpose: Verify that an invalid or missing token returns 401 Unauthorized.
         *  Scenario: authService.getUserIdFromToken returns null (token invalid).
         *  Verify: Response status is 401.
         */
        String invalidToken = "invalid-token";
        LaunchGameRequest request = new LaunchGameRequest(1L);

        when(authService.getUserIdFromToken(invalidToken)).thenReturn(null);

        mockMvc.perform(post("/api/launchGame")
                .header("Authorization", invalidToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void play_ShouldReturnUnauthorized_WhenSessionExpired() throws Exception {
        /*
         *  Expired session test
         *  Purpose: Verify that using an expired or non-existent playToken returns 401 Unauthorized.
         *  Scenario: Redis has no entry for the playToken (session expired).
         *  Verify: Response status is 401.
         */
        String token = "auth-token";
        String expiredPlayToken = "expired-play-token";
        PlayGameRequest request = new PlayGameRequest(expiredPlayToken);

        when(authService.getUserIdFromToken(token)).thenReturn(1L);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("play:session:" + expiredPlayToken)).thenReturn(null); // Session expired

        mockMvc.perform(post("/api/play")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
