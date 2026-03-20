package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginRequest(
    @Schema(description = "Username", example = "testuser")
    String username
) {
}
