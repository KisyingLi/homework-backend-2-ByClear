package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PlayGameRequest(
    @Schema(description = "遊玩授權碼 (從 launchGame 獲取)", example = "uuid-token-string")
    String playToken
) {
}
