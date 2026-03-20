package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PlayGameRequest(
    @Schema(description = "Play authorization code (from launchGame)", example = "uuid-token-string")
    String playToken
) {
}
