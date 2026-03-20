-- 1. Base User Table
CREATE TABLE IF NOT EXISTS `users` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL UNIQUE,
    `total_points` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. Game Items Table
CREATE TABLE IF NOT EXISTS `games` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(100) NOT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. Game Play Record Table
CREATE TABLE IF NOT EXISTS `games_play_record` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `game_id` BIGINT NOT NULL,
    `score` INT NOT NULL,
    `played_at` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. Main Activity Table (Master)
CREATE TABLE IF NOT EXISTS `activity_master` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `activity_key` VARCHAR(50) NOT NULL UNIQUE COMMENT 'Activity Identifier',
    `activity_name` VARCHAR(100) NOT NULL,
    `days_limit` INT NOT NULL DEFAULT 30 COMMENT 'Days limit to complete after registration',
    `total_reward` INT NOT NULL DEFAULT 0 COMMENT 'Total reward after completing all missions',
    `status` VARCHAR(20) DEFAULT 'ACTIVE',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. Mission Detail Table (Detail)
CREATE TABLE IF NOT EXISTS `activity_missions` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `activity_id` BIGINT NOT NULL,
    `mission_type` VARCHAR(50) NOT NULL COMMENT 'Mission type (LOGIN, GAME_LAUNCH, GAME_PLAY)',
    `mission_name` VARCHAR(100) NOT NULL,
    `target_count` INT NOT NULL COMMENT 'Target count or days threshold',
    `target_score` INT DEFAULT 0 COMMENT 'Score threshold',
    `description` VARCHAR(255),
    CONSTRAINT `fk_activity` FOREIGN KEY (`activity_id`) REFERENCES `activity_master` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. Reward Record Table (Reward Records)
CREATE TABLE IF NOT EXISTS `reward_records` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `activity_id` BIGINT NOT NULL,
    `reward_points` INT NOT NULL,
    `claimed_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_user_activity` (`user_id`, `activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Initialize base data
INSERT IGNORE INTO `games` (id, name) VALUES 
(1, '星際冒險'), (2, '超級瑪嘉'), (3, '連連看'), (4, '俄羅斯方塊'), (5, '撲克大賽');

-- Initialize activity data
INSERT IGNORE INTO `activity_master` (activity_key, activity_name, days_limit, total_reward) 
VALUES ('NEW_USER_MISSION', '新用戶 30 天任務', 30, 777);

-- Initialize mission details
INSERT IGNORE INTO `activity_missions` (activity_id, mission_type, mission_name, target_count, target_score) 
SELECT id, 'LOGIN', '累積登入 3 天', 3, 0 FROM activity_master WHERE activity_key = 'NEW_USER_MISSION'
UNION ALL
SELECT id, 'GAME_LAUNCH', '啟動 3 款不同遊戲', 3, 0 FROM activity_master WHERE activity_key = 'NEW_USER_MISSION'
UNION ALL
SELECT id, 'GAME_PLAY', '遊玩 3 次且積分累積 1000 以上', 3, 1000 FROM activity_master WHERE activity_key = 'NEW_USER_MISSION';
