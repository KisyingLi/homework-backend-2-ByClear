package com.example.demo.event;

import java.time.LocalDate;

public record GameLaunchEvent(Long userId, Long gameId, LocalDate launchDate) {
}
