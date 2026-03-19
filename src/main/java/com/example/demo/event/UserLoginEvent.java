package com.example.demo.event;

import java.time.LocalDate;

public record UserLoginEvent(Long userId, LocalDate loginDate) {
}
