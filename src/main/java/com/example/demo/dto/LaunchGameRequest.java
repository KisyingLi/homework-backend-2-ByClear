package com.example.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record LaunchGameRequest(
    @Schema(description = "遊戲 ID", example = "1")
    Long gameId
) {
}
