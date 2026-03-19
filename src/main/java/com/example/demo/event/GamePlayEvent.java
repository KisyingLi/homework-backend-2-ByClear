package com.example.demo.event;

import java.time.LocalDate;

public record GamePlayEvent(Long userId, Long gameId, Integer score, LocalDate playDate) {
}
