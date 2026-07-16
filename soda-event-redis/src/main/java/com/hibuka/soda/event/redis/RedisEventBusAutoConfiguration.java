package com.hibuka.soda.event.redis;

import com.hibuka.soda.bus.configuration.EventProperties;
import com.hibuka.soda.cqrs.event.EventBus;
import com.hibuka.soda.cqrs.event.EventHandler;
import com.hibuka.soda.domain.event.DomainEvent;
import com.hibuka.soda.event.EventSerializer;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;
import java.util.Map;

/**
 * Redis event bus auto-configuration class.
 * <p>
 * Uses {@link EventSerializer} to decouple from any specific JSON library.
 * The ObjectMapper is obtained from the Spring context (auto-configured by Spring Boot),
 * so it works with both Jackson 2 (Spring Boot 3) and Jackson 3 (Spring Boot 4).
 * </p>
 *
 * @author kangzeng.ckz
 * @since 2024/12/09
 */
@Configuration
@AutoConfigureAfter(name = {
    "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
    "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
})
@ConditionalOnClass(RedisTemplate.class)
@ConditionalOnProperty(name = "soda.event.bus-type", havingValue = "redis")
@EnableConfigurationProperties(EventProperties.class)
public class RedisEventBusAutoConfiguration {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(RedisEventBusAutoConfiguration.class);
    private final EventProperties eventProperties;
    private final ApplicationContext applicationContext;

    @Autowired
    public RedisEventBusAutoConfiguration(EventProperties eventProperties, ApplicationContext applicationContext) {
        logger.info("[RedisEventBusAutoConfiguration] Constructor called");
        this.eventProperties = eventProperties;
        this.applicationContext = applicationContext;
        logger.info("[RedisEventBusAutoConfiguration] Bus type: {}", eventProperties.getBusType());
    }

    /**
     * Creates the EventSerializer.
     * ObjectMapper is resolved lazily on first use to ensure Jackson auto-configuration has completed.
     */
    @Bean
    @ConditionalOnMissingBean
    public EventSerializer eventSerializer(ApplicationContext applicationContext) {
        logger.info("[RedisEventBusAutoConfiguration] Creating JacksonEventSerializer");
        return new JacksonEventSerializer(applicationContext);
    }

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisTemplate<String, Object> sodaRedisEventBusTemplate(RedisConnectionFactory redisConnectionFactory) {
        logger.info("[RedisEventBusAutoConfiguration] Creating sodaRedisEventBusTemplate");
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        logger.info("[RedisEventBusAutoConfiguration] Created sodaRedisEventBusTemplate");
        return template;
    }

    @Bean
    @Primary
    @ConditionalOnBean(RedisConnectionFactory.class)
    public EventBus redisStreamEventBus(@Qualifier("sodaRedisEventBusTemplate") RedisTemplate<String, Object> sodaRedisEventBusTemplate,
                                       ApplicationEventPublisher applicationEventPublisher,
                                       RedisConnectionFactory redisConnectionFactory,
                                       List<EventHandler<? extends DomainEvent>> eventHandlers,
                                       EventSerializer eventSerializer) {
        logger.info("[RedisEventBusAutoConfiguration] Creating RedisStreamEventBus (Stream mode)");

        String topicName = eventProperties.getRedis().getTopic();
        String groupName = eventProperties.getRedis().getStream().getGroupName();
        String consumerName = eventProperties.getRedis().getStream().getConsumerName();
        long maxlen = eventProperties.getRedis().getStream().getMaxlen();
        long pollTimeout = eventProperties.getRedis().getStream().getPollTimeout();
        int batchSize = eventProperties.getRedis().getStream().getBatchSize();
        int concurrency = eventProperties.getRedis().getStream().getConcurrency();
        int maxRetries = eventProperties.getRedis().getStream().getMaxRetries();
        long initialRetryDelay = eventProperties.getRedis().getStream().getInitialRetryDelay();
        boolean exponentialBackoff = eventProperties.getRedis().getStream().isExponentialBackoff();
        String deadLetterStream = eventProperties.getRedis().getStream().getDeadLetterStream();

        logger.info("[RedisEventBusAutoConfiguration] Redis Stream configuration: topic={}, group={}, consumer={}, maxlen={}, pollTimeout={}, batchSize={}, concurrency={}, maxRetries={}, initialRetryDelay={}, exponentialBackoff={}, deadLetterStream={}",
                   topicName, groupName, consumerName, maxlen, pollTimeout, batchSize, concurrency, maxRetries, initialRetryDelay, exponentialBackoff, deadLetterStream);

        RedisStreamEventBus redisStreamEventBus = new RedisStreamEventBus.Builder(
            sodaRedisEventBusTemplate,
            applicationEventPublisher,
            redisConnectionFactory,
            eventHandlers,
            topicName,
            eventSerializer
        )
        .groupName(groupName)
        .consumerName(consumerName)
        .maxlen(maxlen)
        .pollTimeout(pollTimeout)
        .batchSize(batchSize)
        .concurrency(concurrency)
        .maxRetries(maxRetries)
        .initialRetryDelay(initialRetryDelay)
        .exponentialBackoff(exponentialBackoff)
        .deadLetterStream(deadLetterStream)
        .idempotencyProperties(eventProperties.getRedis().getStream().getIdempotency())
        .build();

        logger.info("[RedisEventBusAutoConfiguration] Created RedisStreamEventBus (Stream mode)");
        return redisStreamEventBus;
    }
}
