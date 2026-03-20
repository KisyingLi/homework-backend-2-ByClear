package com.example.demo.mq;

import com.example.demo.event.UserLoginEvent;
import com.example.demo.service.MissionService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RocketMQMessageListener(
    topic = "MISSION_TOPIC",
    consumerGroup = "mission_consumer_login",
    selectorExpression = "LOGIN"
)
public class MissionEventConsumer implements RocketMQListener<UserLoginEvent> {

    private static final Logger log = LoggerFactory.getLogger(MissionEventConsumer.class);

    private final MissionService missionService;

    public MissionEventConsumer(MissionService missionService) {
        this.missionService = missionService;
    }

    @Override
    public void onMessage(UserLoginEvent event) {
        log.info("MQ Consumer successfully received login event! UserId={}, LoginDate={}", event.userId(), event.loginDate());
        missionService.recordLogin(event.userId(), event.loginDate());
    }
}
