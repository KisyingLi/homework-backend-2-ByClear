package com.example.demo.enums;

import lombok.Getter;

@Getter
public enum MissionType {
    LOGIN(1, "Login Consecutive Days"),
    GAME_LAUNCH(2, "Distinct Game Launches"),
    GAME_PLAY(3, "Game Play Sessions and Score");

    private final int code;
    private final String description;

    MissionType(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static MissionType fromCode(int code) {
        for (MissionType type : MissionType.values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown mission code: " + code);
    }
}
