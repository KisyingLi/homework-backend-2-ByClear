package com.example.demo.mq;

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
        log.info("MQ Consumer received game launch event! UserId={}, GameId={}", event.userId(), event.gameId());
        missionService.recordLaunch(event.userId(), event.gameId());
    }
}
