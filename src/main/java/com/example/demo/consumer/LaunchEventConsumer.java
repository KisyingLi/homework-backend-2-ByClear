package com.example.demo.consumer;

import com.example.demo.event.GameLaunchEvent;
import com.example.demo.service.MissionService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RocketMQMessageListener(
    topic = "MISSION_TOPIC",
    consumerGroup = "mission_consumer_launch",
    selectorExpression = "LAUNCH"
)
public class LaunchEventConsumer implements RocketMQListener<GameLaunchEvent> {

    private static final Logger log = LoggerFactory.getLogger(LaunchEventConsumer.class);

    private final MissionService missionService;

    public LaunchEventConsumer(MissionService missionService) {
        this.missionService = missionService;
    }

    @Override
    public void onMessage(GameLaunchEvent event) {
        log.info("MQ Consumer 接收到遊戲啟動事件！UserId={}, GameId={}", event.userId(), event.gameId());
        missionService.recordLaunch(event.userId(), event.gameId());
    }
}
