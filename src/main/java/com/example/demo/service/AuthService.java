package com.example.demo.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final StringRedisTemplate redisTemplate;

	public Long getUserIdFromToken(String token) {
		if (token == null || token.trim().isEmpty())
			return null;
		if (token.startsWith("Bearer ")) {
			token = token.substring(7);
		}
		String userIdStr = redisTemplate.opsForValue().get("auth:token:" + token);
		if (userIdStr != null) {
			return Long.parseLong(userIdStr);
		}
		return null;
	}
}
