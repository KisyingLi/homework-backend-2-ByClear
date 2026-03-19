-- 1. 基礎用戶表格
CREATE TABLE IF NOT EXISTS `users` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL UNIQUE,
    `total_points` INT NOT NULL DEFAULT 0,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. 遊戲項目表格
CREATE TABLE IF NOT EXISTS `games` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(100) NOT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3. 遊戲遊玩紀錄表格
CREATE TABLE IF NOT EXISTS `games_play_record` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `game_id` BIGINT NOT NULL,
    `score` INT NOT NULL,
    `played_at` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 主活動表格 (Master)
CREATE TABLE IF NOT EXISTS `activity_master` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `activity_key` VARCHAR(50) NOT NULL UNIQUE COMMENT '活動識別碼',
    `activity_name` VARCHAR(100) NOT NULL,
    `days_limit` INT NOT NULL DEFAULT 30 COMMENT '需在註冊後幾天內完成',
    `total_reward` INT NOT NULL DEFAULT 0 COMMENT '完成全部任務後的獎勵',
    `status` VARCHAR(20) DEFAULT 'ACTIVE',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. 任務細項表格 (Detail)
CREATE TABLE IF NOT EXISTS `activity_missions` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `activity_id` BIGINT NOT NULL,
    `mission_order` INT NOT NULL COMMENT '任務順序編號',
    `mission_name` VARCHAR(100) NOT NULL,
    `target_count` INT NOT NULL COMMENT '目標次數/天數門檻',
    `target_score` INT DEFAULT 0 COMMENT '積分門檻',
    `description` VARCHAR(255),
    CONSTRAINT `fk_activity` FOREIGN KEY (`activity_id`) REFERENCES `activity_master` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. 獎勵領取紀錄表格 (Reward Records)
CREATE TABLE IF NOT EXISTS `reward_records` (
    `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
    `user_id` BIGINT NOT NULL,
    `activity_id` BIGINT NOT NULL,
    `reward_points` INT NOT NULL,
    `claimed_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_user_activity` (`user_id`, `activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 初始化基礎數據
INSERT IGNORE INTO `games` (id, name) VALUES 
(1, '星際冒險'), (2, '超級瑪嘉'), (3, '連連看'), (4, '俄羅斯方塊'), (5, '撲克大賽');

-- 初始化活動數據
INSERT IGNORE INTO `activity_master` (activity_key, activity_name, days_limit, total_reward) 
VALUES ('NEW_USER_MISSION', '新用戶 30 天任務', 30, 777);

-- 初始化任務細項
INSERT IGNORE INTO `activity_missions` (activity_id, mission_order, mission_name, target_count, target_score) 
SELECT id, 1, '累積登入 3 天', 3, 0 FROM activity_master WHERE activity_key = 'NEW_USER_MISSION'
UNION ALL
SELECT id, 2, '啟動 3 款不同遊戲', 3, 0 FROM activity_master WHERE activity_key = 'NEW_USER_MISSION'
UNION ALL
SELECT id, 3, '遊玩 3 次且積分累積 1000 以上', 3, 1000 FROM activity_master WHERE activity_key = 'NEW_USER_MISSION';
