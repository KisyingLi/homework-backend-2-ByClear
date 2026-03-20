package com.example.demo.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CacheSyncListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CacheSyncListener.class);
    private final CacheManager cacheManager;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String activityKey = new String(message.getBody());
        log.info("Received cache refresh message for activity: {}", activityKey);

        var cache = cacheManager.getCache("activities");
        if (cache != null) {
            cache.evict(activityKey);
            log.info("Local cache evicted for activity: {}", activityKey);
        }
    }
}
