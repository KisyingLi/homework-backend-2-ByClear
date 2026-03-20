package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

import com.example.demo.listener.CacheSyncListener;

@Configuration
public class RedisPubSubConfig {

    public static final String ACTIVITY_CACHE_TOPIC = "activity-cache-refresh";

    @Bean
    public RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory,
            MessageListenerAdapter listenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new ChannelTopic(ACTIVITY_CACHE_TOPIC));
        return container;
    }

    @Bean
    public MessageListenerAdapter listenerAdapter(CacheSyncListener listener) {
        return new MessageListenerAdapter(listener, "onMessage");
    }
}
