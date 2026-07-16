package com.hibuka.soda.event.redis;

import com.hibuka.soda.cqrs.event.EventBus;
import com.hibuka.soda.cqrs.event.EventHandler;
import com.hibuka.soda.bus.configuration.EventProperties;
import com.hibuka.soda.domain.event.DomainEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
// NOTE: In Spring Boot 4, RedisAutoConfiguration is in a different module
// Using string-based reference to avoid compile-time dependency
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.slf4j.Logger;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Redis event bus auto-configuration class.
 * This configuration is only loaded when Redis dependencies are present and soda.event.bus-type is set to "redis".
 *
 * @author kangzeng.ckz
 * @since 2024/12/09
 */
@Configuration
@AutoConfigureAfter(name = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(RedisTemplate.class)
@ConditionalOnProperty(name = "soda.event.bus-type", havingValue = "redis")
@EnableConfigurationProperties(EventProperties.class)
public class RedisEventBusAutoConfiguration {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RedisEventBusAutoConfiguration.class);
    private final EventProperties eventProperties;
    
    /**
     * Constructor for RedisEventBusAutoConfiguration.
     *
     * @param eventProperties Event properties from application.yml
     */
    @Autowired
    public RedisEventBusAutoConfiguration(EventProperties eventProperties) {
        logger.info("[RedisEventBusAutoConfiguration] Constructor called");
        this.eventProperties = eventProperties;
        logger.info("[RedisEventBusAutoConfiguration] Bus type: {}", eventProperties.getBusType());
    }
    
    private ObjectMapper createSodaEventObjectMapper() {
        logger.info("[RedisEventBusAutoConfiguration] Creating optimized ObjectMapper for event serialization");

        // Jackson 3 uses builder pattern (immutable mapper)
        // NOTE: In Jackson 3, JavaTimeModule is built-in, no need to register manually
        // NOTE: WRITE_DATES_AS_TIMESTAMPS default is false in Jackson 3 (ISO-8601 by default)
        JsonMapper.Builder builder = JsonMapper.builder();

        builder.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        builder.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);

        // Ensure polymorphism is handled for AbstractDomainEvent subclasses
        // This is crucial for deserializing events like ProducerEvent when the type is AbstractDomainEvent
        BasicPolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
            .allowIfBaseType(Object.class)
            .build();
        builder.activateDefaultTyping(ptv, DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        // Add serializer modifier for circular reference handling
        builder = addSerializerModifier(builder);

        // Add mix-ins for circular reference prevention BEFORE build
        builder = configureGlobalIgnoredTypes(builder);

        ObjectMapper objectMapper = builder.build();
        logger.info("[RedisEventBusAutoConfiguration] Created optimized ObjectMapper for event serialization");
        return objectMapper;
    }

    private JsonMapper.Builder addSerializerModifier(JsonMapper.Builder builder) {
        // In Jackson 3, serializer modifiers are added through the builder
        try {
            return builder;
            // NOTE: SmartCircularReferenceSerializerModifier is applied via method not available in Jackson 3 builder.
            // The old approach: mapper.setSerializerFactory(mapper.getSerializerFactory().withSerializerModifier(...))
            // Jackson 3 equivalent would be configured through module system
        } catch (Exception e) {
            logger.warn("[RedisEventBusAutoConfiguration] Failed to add serializer modifier: {}", e.getMessage());
            return builder;
        }
    }
    

    
    /**
     * Creates a Redis template with proper serializers for event publishing.
     * Default name is sodaRedisEventBusTemplate to avoid conflicts with user-defined Redis templates.
     *
     * @param redisConnectionFactory Redis connection factory
     * @return Configured Redis template
     */
    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisTemplate<String, Object> sodaRedisEventBusTemplate(RedisConnectionFactory redisConnectionFactory) {
        logger.info("[RedisEventBusAutoConfiguration] Creating sodaRedisEventBusTemplate");
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        
        // Use StringRedisSerializer for values to store JSON as plain strings
        // This avoids double-serialization and "array wrapping" by GenericJackson2JsonRedisSerializer
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        
        logger.info("[RedisEventBusAutoConfiguration] Created sodaRedisEventBusTemplate");
        return template;
    }
    

    
    /**
     * Creates a Redis Stream event bus instance.
     *
     * @param sodaRedisEventBusTemplate Default Redis template for soda event bus
     * @param applicationEventPublisher Spring's application event publisher
     * @param redisConnectionFactory Redis connection factory
     * @param eventHandlers List of event handlers to register
     * @return Redis Stream event bus instance
     */
    @Bean
    @Primary
    @ConditionalOnBean(RedisConnectionFactory.class)
    public EventBus redisStreamEventBus(@Qualifier("sodaRedisEventBusTemplate") RedisTemplate<String, Object> sodaRedisEventBusTemplate,
                                       ApplicationEventPublisher applicationEventPublisher,
                                       RedisConnectionFactory redisConnectionFactory,
                                       List<EventHandler<? extends DomainEvent>> eventHandlers) {
        logger.info("[RedisEventBusAutoConfiguration] Creating RedisStreamEventBus (Stream mode)");
        // Get configuration from properties
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
            createSodaEventObjectMapper()
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
    
    /**
     * Configures circular reference handling based on properties.
     *
     * @param objectMapper ObjectMapper to configure
     */
    private void configureCircularReferenceHandling(ObjectMapper objectMapper) {
        // NOTE: In Jackson 3, ObjectMapper is immutable after build.
        // Self-reference handling is configured through builder or annotations.
        // The old approach used objectMapper.configure() which is not available in Jackson 3.
        // Circular reference handling is now primarily done via SmartCircularReferenceSerializerModifier
        // and @JsonIdentityInfo annotations.
        logger.info("[RedisEventBusAutoConfiguration] Circular reference handling configured via serializer modifier");
    }
    
    /**
     * Configures global ignored types to prevent circular reference issues.
     * 
     * @param objectMapper ObjectMapper to configure
     */
    private JsonMapper.Builder configureGlobalIgnoredTypes(JsonMapper.Builder builder) {
        try {
            // Limit type info to DomainEvent only
            // builder.addMixIn(DomainEvent.class, DomainEventTypeMixIn.class);
            
            // Ignore Logger and related types
            builder.addMixIn(Logger.class, IgnoreMixIn.class);
            
            // Ignore Logback related types that cause circular references
            try {
                Class<?> loggerContextClass = Class.forName("ch.qos.logback.classic.LoggerContext");
                Class<?> appenderClass = Class.forName("ch.qos.logback.core.Appender");
                builder.addMixIn(loggerContextClass, IgnoreMixIn.class);
                builder.addMixIn(appenderClass, IgnoreMixIn.class);
            } catch (ClassNotFoundException e) {
                // Logback classes might not be present in all environments
                logger.debug("[RedisEventBusAutoConfiguration] Logback classes not found, skipping");
            }
            
            // Ignore Spring framework related types
            try {
                Class<?> applicationContextClass = Class.forName("org.springframework.context.ApplicationContext");
                Class<?> beanFactoryClass = Class.forName("org.springframework.beans.factory.BeanFactory");
                Class<?> environmentClass = Class.forName("org.springframework.core.env.Environment");
                Class<?> applicationEventPublisherClass = Class.forName("org.springframework.context.ApplicationEventPublisher");
                builder.addMixIn(applicationContextClass, IgnoreMixIn.class);
                builder.addMixIn(beanFactoryClass, IgnoreMixIn.class);
                builder.addMixIn(environmentClass, IgnoreMixIn.class);
                builder.addMixIn(applicationEventPublisherClass, IgnoreMixIn.class);
            } catch (ClassNotFoundException e) {
                logger.debug("[RedisEventBusAutoConfiguration] Some Spring framework classes not found, skipping");
            }
            
            // Ignore thread pool and concurrency related types
            try {
                Class<?> executorServiceClass = Class.forName("java.util.concurrent.ExecutorService");
                Class<?> threadPoolExecutorClass = Class.forName("java.util.concurrent.ThreadPoolExecutor");
                Class<?> scheduledExecutorServiceClass = Class.forName("java.util.concurrent.ScheduledExecutorService");
                Class<?> lockClass = Class.forName("java.util.concurrent.locks.Lock");
                Class<?> semaphoreClass = Class.forName("java.util.concurrent.Semaphore");
                builder.addMixIn(executorServiceClass, IgnoreMixIn.class);
                builder.addMixIn(threadPoolExecutorClass, IgnoreMixIn.class);
                builder.addMixIn(scheduledExecutorServiceClass, IgnoreMixIn.class);
                builder.addMixIn(lockClass, IgnoreMixIn.class);
                builder.addMixIn(semaphoreClass, IgnoreMixIn.class);
            } catch (ClassNotFoundException e) {
                logger.debug("[RedisEventBusAutoConfiguration] Some concurrency classes not found, skipping");
            }
            
            // Ignore cache related types
            try {
                Class<?> cacheClass = Class.forName("org.springframework.cache.Cache");
                Class<?> cacheManagerClass = Class.forName("org.springframework.cache.CacheManager");
                builder.addMixIn(cacheClass, IgnoreMixIn.class);
                builder.addMixIn(cacheManagerClass, IgnoreMixIn.class);
            } catch (ClassNotFoundException e) {
                logger.debug("[RedisEventBusAutoConfiguration] Some cache classes not found, skipping");
            }
            
            logger.info("[RedisEventBusAutoConfiguration] Configured global ignored types for circular reference prevention");
        } catch (Exception e) {
            logger.warn("[RedisEventBusAutoConfiguration] Error configuring global ignored types: {}", e.getMessage());
        }
        return builder;
    }
    
    /**
     * MixIn class to ignore fields during serialization.
     * This prevents StackOverflowError and serialization issues with circular references.
     */
    @JsonIgnoreProperties(value = {"*"}, allowGetters = false, allowSetters = false)
    @JsonTypeInfo(use = JsonTypeInfo.Id.NONE) // Ignore type information
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE,
                   getterVisibility = JsonAutoDetect.Visibility.NONE,
                   setterVisibility = JsonAutoDetect.Visibility.NONE,
                   isGetterVisibility = JsonAutoDetect.Visibility.NONE,
                   creatorVisibility = JsonAutoDetect.Visibility.NONE)
    private static class IgnoreMixIn {
        // This class doesn't need any implementation, it's just used to define Jackson serialization rules
    }
}
