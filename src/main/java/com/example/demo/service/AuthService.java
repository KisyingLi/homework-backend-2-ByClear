package com.example.demo.service;

import org.springframework.stereotype.Service;

import com.example.demo.helper.RedisHelper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final RedisHelper redis;

	public Long getUserIdFromToken(String token) {
		if (token == null || token.trim().isEmpty())
			return null;
		if (token.startsWith("Bearer ")) {
			token = token.substring(7);
		}
		String userIdStr = redis.get(redis.authTokenKey(token));
		return userIdStr != null ? Long.parseLong(userIdStr) : null;
	}
}
