package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginRequest(
    @Schema(description = "使用者名稱", example = "testuser")
    String username
) {
}
