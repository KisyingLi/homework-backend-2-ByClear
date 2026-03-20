package com.example.demo.mq;

import com.example.demo.event.GamePlayEvent;
import com.example.demo.service.MissionService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RocketMQMessageListener(
    topic = "MISSION_TOPIC",
    consumerGroup = "mission_consumer_play",
    selectorExpression = "PLAY"
)
public class PlayEventConsumer implements RocketMQListener<GamePlayEvent> {

    private static final Logger log = LoggerFactory.getLogger(PlayEventConsumer.class);

    private final MissionService missionService;

    public PlayEventConsumer(MissionService missionService) {
        this.missionService = missionService;
    }

    @Override
    public void onMessage(GamePlayEvent event) {
        log.info("MQ Consumer received play result event! UserId={}, GameId={}, Score={}", event.userId(), event.gameId(), event.score());
        missionService.recordPlay(event.userId(), event.gameId(), event.score());
    }
}
