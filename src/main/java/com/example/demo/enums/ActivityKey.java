package com.example.demo.enums;

import lombok.Getter;

@Getter
public enum ActivityKey {
    NEW_USER_MISSION(1, "NEW_USER_MISSION", "New User Welcome Mission");

    private final int code;
    private final String key;
    private final String description;

    ActivityKey(int code, String key, String description) {
        this.code = code;
        this.key = key;
        this.description = description;
    }

    public static ActivityKey fromCode(int code) {
        for (ActivityKey type : ActivityKey.values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown activity code: " + code);
    }
}
