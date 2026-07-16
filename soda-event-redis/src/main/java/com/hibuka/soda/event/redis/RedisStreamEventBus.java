package com.hibuka.soda.event.redis;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.json.JsonMapper;
import com.hibuka.soda.bus.configuration.EventProperties;
import com.hibuka.soda.cqrs.event.EventBus;
import com.hibuka.soda.cqrs.event.EventHandler;
import com.hibuka.soda.domain.event.DomainEvent;
import com.hibuka.soda.context.CommandContext;
import com.hibuka.soda.context.CommandContextHolder;
import com.hibuka.soda.event.redis.service.IdempotencyService;
import com.hibuka.soda.event.redis.service.impl.RedisIdempotencyServiceImpl;
import com.hibuka.soda.foundation.error.BaseErrorCode;
import com.hibuka.soda.foundation.error.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.GenericTypeResolver;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis event bus implementation using Redis Stream mechanism.
 * This implementation supports message persistence, consumer groups, and message acknowledgment.
 *
 * @author kangzeng.ckz
 * @since 2025/12/12
 */
public class RedisStreamEventBus implements EventBus, InitializingBean {
    private static final Logger logger = LoggerFactory.getLogger(RedisStreamEventBus.class);
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate streamRedisTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final RedisConnectionFactory redisConnectionFactory;
    private final String streamKey;
    private final String groupName;
    private final String consumerName;
    private final long maxlen;
    private final long pollTimeout;
    private final int batchSize;
    private final int concurrency;
    private final String deadLetterStream;
    private final int maxRetries;
    private final long initialRetryDelay;
    private final boolean exponentialBackoff;
    private final EventProperties.RedisProperties.StreamProperties.IdempotencyProperties idempotencyProperties;
    private final List<EventHandler<? extends DomainEvent>> eventHandlers;
    
    private final Map<Class<? extends DomainEvent>, List<EventHandler>> handlers = new ConcurrentHashMap<>();
    private final Map<String, Class<? extends DomainEvent>> eventTypeToClassMap = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    
    private StreamMessageListenerContainer<?, ?> container;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Private constructor for RedisStreamEventBus, used by Builder.
     *
     * @param builder the builder containing all configuration
     */
    private RedisStreamEventBus(Builder builder) {
        logger.info("[RedisStreamEventBus] Constructor called, instance: {}", this.hashCode());
        this.redisTemplate = builder.redisTemplate;
        this.streamRedisTemplate = new StringRedisTemplate(builder.redisConnectionFactory);
        this.applicationEventPublisher = builder.applicationEventPublisher;
        this.redisConnectionFactory = builder.redisConnectionFactory;
        this.streamKey = builder.streamKey;
        this.groupName = builder.groupName;
        this.consumerName = builder.consumerName;
        this.maxlen = builder.maxlen;
        this.pollTimeout = builder.pollTimeout;
        this.batchSize = builder.batchSize;
        this.concurrency = builder.concurrency;
        this.maxRetries = builder.maxRetries;
        this.initialRetryDelay = builder.initialRetryDelay;
        this.exponentialBackoff = builder.exponentialBackoff;
        this.deadLetterStream = builder.deadLetterStream;
        this.idempotencyProperties = builder.idempotencyProperties;
        this.eventHandlers = builder.eventHandlers;
        
        // Use the optimized shared ObjectMapper
        this.objectMapper = builder.objectMapper;
        
        // Initialize idempotency service
        this.idempotencyService = new RedisIdempotencyServiceImpl(redisTemplate, idempotencyProperties);
        
        // Register event handlers
        logger.info("[RedisStreamEventBus] Registering {} event handlers, instance: {}", eventHandlers.size(), this.hashCode());
        registerEventHandlers(eventHandlers);
        logger.info("[RedisStreamEventBus] Constructor completed, instance: {}", this.hashCode());
    }

    /**
     * Builder class for RedisStreamEventBus.
     * Provides a fluent API for creating RedisStreamEventBus instances.
     */
    public static class Builder {
        // Required parameters
        private final RedisTemplate<String, Object> redisTemplate;
        private final ApplicationEventPublisher applicationEventPublisher;
        private final RedisConnectionFactory redisConnectionFactory;
        private final List<EventHandler<? extends DomainEvent>> eventHandlers;
        private final String streamKey;
        private final ObjectMapper objectMapper;
        
        // Optional parameters with default values
        private String groupName = "soda-event-group";
        private String consumerName = "soda-event-consumer";
        private long maxlen = 10000;
        private long pollTimeout = 100;
        private int batchSize = 10;
        private int concurrency = 2;
        private int maxRetries = 3;
        private long initialRetryDelay = 1000;
        private boolean exponentialBackoff = true;
        private String deadLetterStream = "soda-dead-letter-stream";
        private EventProperties.RedisProperties.StreamProperties.IdempotencyProperties idempotencyProperties;
        
        /**
         * Constructor for Builder with required parameters.
         *
         * @param redisTemplate Redis template for Stream operations
         * @param applicationEventPublisher Spring's application event publisher
         * @param redisConnectionFactory Redis connection factory
         * @param eventHandlers List of event handlers to register
         * @param streamKey Stream key name for event publishing
         * @param objectMapper Optimized ObjectMapper for serialization/deserialization
         */
        public Builder(RedisTemplate<String, Object> redisTemplate,
                      ApplicationEventPublisher applicationEventPublisher,
                      RedisConnectionFactory redisConnectionFactory,
                      List<EventHandler<? extends DomainEvent>> eventHandlers,
                      String streamKey,
                      ObjectMapper objectMapper) {
            this.redisTemplate = redisTemplate;
            this.applicationEventPublisher = applicationEventPublisher;
            this.redisConnectionFactory = redisConnectionFactory;
            this.eventHandlers = eventHandlers;
            this.streamKey = streamKey;
            this.objectMapper = objectMapper;
        }
        
        /**
         * Sets the consumer group name.
         *
         * @param groupName Consumer group name
         * @return this Builder for method chaining
         */
        public Builder groupName(String groupName) {
            this.groupName = groupName;
            return this;
        }
        
        /**
         * Sets the consumer name.
         *
         * @param consumerName Consumer name
         * @return this Builder for method chaining
         */
        public Builder consumerName(String consumerName) {
            this.consumerName = consumerName;
            return this;
        }
        
        /**
         * Sets the maximum stream length.
         *
         * @param maxlen Maximum stream length
         * @return this Builder for method chaining
         */
        public Builder maxlen(long maxlen) {
            this.maxlen = maxlen;
            return this;
        }
        
        /**
         * Sets the poll timeout in milliseconds.
         *
         * @param pollTimeout Poll timeout in milliseconds
         * @return this Builder for method chaining
         */
        public Builder pollTimeout(long pollTimeout) {
            this.pollTimeout = pollTimeout;
            return this;
        }
        
        /**
         * Sets the batch size for pulling messages.
         *
         * @param batchSize Batch size for pulling messages
         * @return this Builder for method chaining
         */
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }
        
        /**
         * Sets the number of concurrent consumers.
         *
         * @param concurrency Number of concurrent consumers
         * @return this Builder for method chaining
         */
        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }
        
        /**
         * Sets the maximum number of retries.
         *
         * @param maxRetries Maximum number of retries
         * @return this Builder for method chaining
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }
        
        /**
         * Sets the initial retry delay in milliseconds.
         *
         * @param initialRetryDelay Initial retry delay in milliseconds
         * @return this Builder for method chaining
         */
        public Builder initialRetryDelay(long initialRetryDelay) {
            this.initialRetryDelay = initialRetryDelay;
            return this;
        }
        
        /**
         * Sets whether to use exponential backoff.
         *
         * @param exponentialBackoff Whether to use exponential backoff
         * @return this Builder for method chaining
         */
        public Builder exponentialBackoff(boolean exponentialBackoff) {
            this.exponentialBackoff = exponentialBackoff;
            return this;
        }
        
        /**
         * Sets the name of the dead letter stream.
         *
         * @param deadLetterStream Name of the dead letter stream
         * @return this Builder for method chaining
         */
        public Builder deadLetterStream(String deadLetterStream) {
            this.deadLetterStream = deadLetterStream;
            return this;
        }
        
        /**
         * Sets the idempotency properties.
         *
         * @param idempotencyProperties Idempotency configuration properties
         * @return this Builder for method chaining
         */
        public Builder idempotencyProperties(EventProperties.RedisProperties.StreamProperties.IdempotencyProperties idempotencyProperties) {
            this.idempotencyProperties = idempotencyProperties;
            return this;
        }
        
        /**
         * Builds and returns a new RedisStreamEventBus instance.
         *
         * @return a new RedisStreamEventBus instance
         */
        public RedisStreamEventBus build() {
            return new RedisStreamEventBus(this);
        }
    }
    
    /**
     * Registers all event handlers.
     *
     * @param eventHandlers List of event handlers to register
     */
    private void registerEventHandlers(List<EventHandler<? extends DomainEvent>> eventHandlers) {
        logger.info("[RedisStreamEventBus] Registering {} event handlers", eventHandlers.size());
        for (EventHandler<? extends DomainEvent> handler : eventHandlers) {
            Class<?>[] typeArguments = GenericTypeResolver.resolveTypeArguments(
                    handler.getClass(),
                    EventHandler.class
            );
            if (typeArguments != null && typeArguments.length > 0) {
                Class<? extends DomainEvent> eventType = (Class<? extends DomainEvent>) typeArguments[0];
                try {
                    subscribe(eventType, handler);
                    logger.info("[RedisStreamEventBus] Registered handler for event: {}, handlerClass: {}", eventType.getName(), handler.getClass().getName());
                } catch (BaseException e) {
                    logger.error("[RedisStreamEventBus] Error registering handler for event {}: {}", eventType.getName(), e.getMessage(), e);
                    throw new RuntimeException("Failed to register event handler", e);
                }
            }
        }
    }
    
    @Override
    public void afterPropertiesSet() throws Exception {
        if (initialized.compareAndSet(false, true)) {
            logger.info("[RedisStreamEventBus] Initializing Redis Stream event bus");
            
            // Create stream and consumer group if they don't exist
            createStreamAndGroup();
            
            // Set up stream listener container
            configureStreamListener();
            
            logger.info("[RedisStreamEventBus] Initialized Redis Stream event bus");
        }
    }
    
    /**
     * Creates the stream and consumer group if they don't exist.
     */
    private void createStreamAndGroup() {
        try {
            // Check if stream exists, create if not
            Boolean exists = streamRedisTemplate.hasKey(streamKey);
            if (exists == null || !exists) {
                logger.info("[RedisStreamEventBus] Creating stream: {}", streamKey);
                // Create stream with initial entry to ensure it exists
                Map<String, String> initialEntry = new HashMap<>();
                initialEntry.put("type", "INIT");
                streamRedisTemplate.opsForStream().add(streamKey, initialEntry);
                logger.info("[RedisStreamEventBus] Stream created: {}", streamKey);
            }
            
            // Create consumer group
            try {
                streamRedisTemplate.opsForStream().createGroup(streamKey, groupName);
                logger.info("[RedisStreamEventBus] Consumer group created: {}", groupName);
            } catch (Exception e) {
                // Group likely already exists, log and continue
                logger.info("[RedisStreamEventBus] Consumer group likely already exists: {}", groupName);
            }
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error creating stream or consumer group: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Redis Stream event bus", e);
        }
    }
    
    /**
     * Configures the stream listener container for message consumption.
     */
    private void configureStreamListener() {
        logger.info("[RedisStreamEventBus] Configuring stream listener...");
        // Create container options with String serializers to match RedisTemplate's String keys and JSON values
        @SuppressWarnings("rawtypes")
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions options = 
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofMillis(pollTimeout))
                        .batchSize(batchSize)
                        .keySerializer(new StringRedisSerializer()) // For Stream Key
                        .hashKeySerializer(new StringRedisSerializer()) // For Map Keys
                        .hashValueSerializer(new StringRedisSerializer()) // For Map Values (read as JSON String)
                        .errorHandler(throwable -> {
                            // Handle Redis connection and other exceptions gracefully
                            logger.warn("[RedisStreamEventBus] Stream listener error, will retry: {}", throwable.getMessage());
                            // Don't rethrow - let container continue running
                        })
                        .build();
        
        // Create container with String types
        @SuppressWarnings({"rawtypes", "unchecked"})
        StreamMessageListenerContainer container = StreamMessageListenerContainer.create(redisConnectionFactory, options);
        this.container = container;
        
        // Create stream listener that handles raw MapRecord with explicit type
        StreamListener<String, MapRecord<String, String, String>> listener = new StreamListener<>() {
            @Override
            public void onMessage(MapRecord<String, String, String> message) {
                logger.info("[RedisStreamEventBus] Raw onMessage received: {}", message.getId());
                try {
                    handleStreamMessageWithRetry(message);
                } catch (Exception e) {
                    logger.error("[RedisStreamEventBus] Error handling stream message: {}", e.getMessage(), e);
                }
            }
        };
        
        // Start concurrency loop to create multiple consumers
        ReadOffset offset = ReadOffset.lastConsumed();
        StreamOffset<String> streamOffset = StreamOffset.create(streamKey, offset);
        
        for (int i = 0; i < concurrency; i++) {
            String currentConsumerName = concurrency > 1 ? consumerName + "-" + i : consumerName;
            Consumer consumer = Consumer.from(groupName, currentConsumerName);
            
            // Use manual ACK by calling receive() instead of receiveAutoAck()
            @SuppressWarnings({"rawtypes", "unchecked"})
            Subscription sub = container.receive(consumer, streamOffset, listener);
            this.subscriptions.add(sub);
            logger.info("[RedisStreamEventBus] Created consumer subscription: {}", currentConsumerName);
        }
        
        // Start the container
        container.start();
        logger.info("[RedisStreamEventBus] Stream listener container started with options: pollTimeout={}, batchSize={}, concurrency={}", 
                   pollTimeout, batchSize, concurrency);
    }
    
    /**
     * Handles a stream message with retry logic, dead letter queue functionality, and idempotency checks.
     *
     * @param message The stream message to handle
     */
    private void handleStreamMessageWithRetry(MapRecord<String, String, String> message) {
        logger.info("[RedisStreamEventBus] Received stream message: ID={}, Stream={}", message.getId(), message.getStream());
        
        boolean processed = false;
        String eventId = null;
        
        try {
            // Extract eventId directly from message for idempotency check, without full deserialization
            eventId = extractEventIdFromMessage(message);
            
            // Idempotency check: if enabled and event has ID, check if it's already processed
            if (idempotencyProperties.isEnabled() && eventId != null) {
                IdempotencyService.ProcessingStatus status = idempotencyService.getStatus(eventId);
                if (status == IdempotencyService.ProcessingStatus.SUCCESS) {
                    logger.info("[RedisStreamEventBus] Event already processed successfully, skipping: eventId={}, messageId={}", 
                               eventId, message.getId());
                    // Acknowledge the message immediately since it's already processed
                    streamRedisTemplate.opsForStream().acknowledge(streamKey, groupName, message.getId());
                    return;
                } else if (status == IdempotencyService.ProcessingStatus.PROCESSING) {
                    logger.info("[RedisStreamEventBus] Event currently processing, skipping: eventId={}, messageId={}", 
                               eventId, message.getId());
                    // Don't acknowledge yet, let the processing instance handle it
                    return;
                }
            }
            
            for (int retryCount = 0; retryCount <= maxRetries; retryCount++) {
                try {
                    // Begin processing with idempotency check
                    boolean canProcess = true;
                    if (idempotencyProperties.isEnabled() && eventId != null) {
                        canProcess = idempotencyService.beginProcessing(eventId);
                    }
                    
                    if (canProcess) {
                        processed = handleStreamMessageInternal(message);
                        if (processed) {
                            // Mark as success if idempotency is enabled
                            if (idempotencyProperties.isEnabled() && eventId != null) {
                                idempotencyService.markAsSuccess(eventId, new HashMap<>());
                            }
                            // Acknowledge the message using RedisTemplate
                            streamRedisTemplate.opsForStream().acknowledge(streamKey, groupName, message.getId());
                            logger.info("[RedisStreamEventBus] Successfully processed message after {} retries: ID={}", retryCount, message.getId());
                            break;
                        } else {
                            // Mark as failed if idempotency is enabled
                            if (idempotencyProperties.isEnabled() && eventId != null) {
                                idempotencyService.markAsFailed(eventId, "Processing returned false");
                            }
                        }
                    } else {
                        logger.info("[RedisStreamEventBus] Cannot process event due to idempotency check: eventId={}, messageId={}", 
                                   eventId, message.getId());
                        // Acknowledge if we can't process due to idempotency
                        streamRedisTemplate.opsForStream().acknowledge(streamKey, groupName, message.getId());
                        break;
                    }
                } catch (Exception e) {
                    // Mark as failed if idempotency is enabled
                    if (idempotencyProperties.isEnabled() && eventId != null) {
                        idempotencyService.markAsFailed(eventId, e.getMessage());
                    }
                    
                    if (retryCount < maxRetries) {
                        long delay = calculateRetryDelay(retryCount);
                        logger.warn("[RedisStreamEventBus] Failed to process message, retrying in {}ms (attempt {}/{}): ID={}, Error: {}", 
                                   delay, retryCount + 1, maxRetries + 1, message.getId(), e.getMessage());
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            logger.error("[RedisStreamEventBus] Retry sleep interrupted: {}", ie.getMessage());
                            break;
                        }
                    } else {
                        logger.error("[RedisStreamEventBus] Maximum retries exceeded, moving to dead letter queue: ID={}", message.getId());
                        moveToDeadLetterQueue(message, "Max retries exceeded");
                        // Acknowledge the original message after moving to dead letter queue
                        streamRedisTemplate.opsForStream().acknowledge(streamKey, groupName, message.getId());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error in message handling flow: {}", e.getMessage(), e);
        }
        
        if (!processed) {
            logger.error("[RedisStreamEventBus] Message processing failed without exception, moving to dead letter queue: ID={}", message.getId());
            moveToDeadLetterQueue(message, "Processing failed without exception");
            // Acknowledge the original message after moving to dead letter queue
            streamRedisTemplate.opsForStream().acknowledge(streamKey, groupName, message.getId());
        }
    }
    
    /**
     * Extracts the eventId from a stream message without full deserialization.
     * This is optimized for idempotency checks, avoiding the need to fully deserialize events.
     *
     * @param message The stream message to extract eventId from
     * @return eventId if extracted successfully, null otherwise
     */
    private String extractEventIdFromMessage(MapRecord<String, String, String> message) {
        try {
            // Get the serialized event from the message
            String serializedEvent = message.getValue().get("event");
            if (serializedEvent == null) {
                return null;
            }
            
            logger.debug("[RedisStreamEventBus] Extracting eventId from serialized event: {}", serializedEvent);
            
            // In Jackson 3, ObjectMapper is immutable; use builder pattern
            ObjectMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
            
            try {
                // Parse the JSON to get eventId directly
                JsonNode rootNode = mapper.readTree(serializedEvent);
                
                // Handle both array format [eventType, eventData] and direct object format
                JsonNode eventDataNode = rootNode;
                if (rootNode.isArray() && rootNode.size() >= 2) {
                    // Format is [eventType, eventData]
                    logger.debug("[RedisStreamEventBus] Handling array format: [eventType, eventData]");
                    eventDataNode = rootNode.get(1);
                }
                
                // Extract eventId from eventData
                if (eventDataNode.isObject()) {
                    logger.debug("[RedisStreamEventBus] Event data is object, extracting eventId");
                    JsonNode eventIdNode = eventDataNode.get("eventId");
                    if (eventIdNode != null && eventIdNode.isTextual()) {
                        String eventId = eventIdNode.asText();
                        logger.info("[RedisStreamEventBus] Successfully extracted eventId from message: {}", eventId);
                        return eventId;
                    } else {
                        logger.warn("[RedisStreamEventBus] eventId field not found or not textual in eventData");
                    }
                } else {
                    logger.warn("[RedisStreamEventBus] Event data is not an object, cannot extract eventId");
                }
            } catch (Exception e) {
                logger.debug("[RedisStreamEventBus] Failed to extract eventId from message: {}", e.getMessage());
                // Fallback: try full deserialization as last resort
                DomainEvent event = extractEventFromMessage(message);
                if (event != null) {
                    String eventId = event.getEventId();
                    logger.info("[RedisStreamEventBus] Extracted eventId from full deserialization: {}", eventId);
                    return eventId;
                }
            }
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error extracting eventId from message: {}", e.getMessage(), e);
        }
        return null;
    }
    
    /**
     * Extracts the DomainEvent from a stream message without processing it.
     * Used as fallback when direct eventId extraction fails.
     *
     * @param message The stream message to extract event from
     * @return DomainEvent if extracted successfully, null otherwise
     */
    private DomainEvent extractEventFromMessage(MapRecord<String, String, String> message) {
        try {
            // Get the serialized event and type from the message
            String serializedEvent = message.getValue().get("event");
            String eventType = message.getValue().get("type");
            
            if (serializedEvent == null || eventType == null) {
                return null;
            }
            
            // Get the event class from the type map
            Class<? extends DomainEvent> eventClass = eventTypeToClassMap.get(eventType);
            
            // Fallback: handle quoted event types (e.g., when RedisTemplate adds quotes)
            if (eventClass == null) {
                // Try removing quotes if present
                String unquotedEventType = eventType;
                if ((eventType.startsWith("\"") && eventType.endsWith("\"")) || 
                    (eventType.startsWith("'") && eventType.endsWith("'"))) {
                    unquotedEventType = eventType.substring(1, eventType.length() - 1);
                    eventClass = eventTypeToClassMap.get(unquotedEventType);
                }
            }
            
            if (eventClass == null) {
                return null;
            }
            
            // Deserialize the event from JSON string
            return deserializeDomainEvent(serializedEvent, eventClass);
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error extracting event from message: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Internal method to handle stream message processing without retry logic.
     *
     * @param message The stream message to handle
     * @return true if processing was successful, false otherwise
     * @throws Exception if an error occurs during processing
     */
    private boolean handleStreamMessageInternal(MapRecord<String, String, String> message) throws Exception {
        // Deserialize context
        String contextJson = message.getValue().get("context");
        if (contextJson != null) {
            try {
                CommandContext context = objectMapper.readValue(contextJson, CommandContext.class);
                CommandContextHolder.setContext(context);
            } catch (Exception e) {
                logger.warn("[RedisStreamEventBus] Failed to deserialize context", e);
            }
        }

        try {
            // Get the serialized event and type from the message
            String serializedEvent = message.getValue().get("event");
            String eventType = message.getValue().get("type");
            
            if (serializedEvent == null) {
                logger.error("[RedisStreamEventBus] Missing 'event' field in stream message");
                return false;
            }
            
            if (eventType == null) {
                logger.error("[RedisStreamEventBus] Missing 'type' field in stream message");
                return false;
            }
            
            // Get the event class from the type map
            Class<? extends DomainEvent> eventClass = eventTypeToClassMap.get(eventType);
            
            // Fallback: handle quoted event types (e.g., when RedisTemplate adds quotes)
            if (eventClass == null) {
                // Try removing quotes if present
                String unquotedEventType = eventType;
                if ((eventType.startsWith("\"") && eventType.endsWith("\"")) || 
                    (eventType.startsWith("'") && eventType.endsWith("'"))) {
                    unquotedEventType = eventType.substring(1, eventType.length() - 1);
                    logger.debug("[RedisStreamEventBus] Trying unquoted event type: {}", unquotedEventType);
                    eventClass = eventTypeToClassMap.get(unquotedEventType);
                }
            }
            
            if (eventClass == null) {
                logger.error("[RedisStreamEventBus] No registered handler for event type: {}", eventType);
                return false;
            }
            List<EventHandler> localHandlers = handlers.get(eventClass);
            int handlerCount = localHandlers == null ? 0 : localHandlers.size();
            logger.info("[RedisStreamEventBus] Resolved event class: {}, handlers registered: {}", eventClass.getName(), handlerCount);
            
            // Deserialize the event from JSON string using the comprehensive deserializeDomainEvent method
            logger.debug("[RedisStreamEventBus] Deserializing event from JSON: {}", serializedEvent);
            
            DomainEvent event = deserializeDomainEvent(serializedEvent, eventClass);
            
            if (event != null) {
                // Publish to local handlers - this ensures idempotency checks are applied
                publishToLocalHandlers(event);
                
                // Do NOT publish to Spring application event publisher to avoid duplicate processing
                // applicationEventPublisher.publishEvent(event); // Removed to avoid duplicate processing
                
                logger.info("[RedisStreamEventBus] Successfully processed event: {}", event.getClass().getName());
            } else {
                // This is expected behavior if the event has been handled locally
                logger.info("[RedisStreamEventBus] Event deserialization returned null, which is expected for domain events handled locally. Acknowledging message.");
            }
            
            return true; // Always return true to avoid unnecessary retries and DLQ entries
        } finally {
            if (contextJson != null) {
                CommandContextHolder.clearContext();
            }
        }
    }
    
    /**
     * Calculates the retry delay based on the retry count and exponential backoff setting.
     *
     * @param retryCount The current retry count (0-based)
     * @return The calculated delay in milliseconds
     */
    private long calculateRetryDelay(int retryCount) {
        if (exponentialBackoff) {
            return (long) (initialRetryDelay * Math.pow(2, retryCount));
        }
        return initialRetryDelay;
    }
    
    /**
     * Moves a message to the dead letter queue.
     *
     * @param message The message to move
     * @param reason The reason for moving to dead letter queue
     */
    private void moveToDeadLetterQueue(MapRecord<String, String, String> message, String reason) {
        try {
            // Use a more efficient capacity calculation based on message size + additional fields
            Map<String, String> deadLetterEntry = new HashMap<>(message.getValue().size() + 4);
            deadLetterEntry.putAll(message.getValue());
            deadLetterEntry.put("deadLetterReason", reason);
            deadLetterEntry.put("deadLetterTimestamp", String.valueOf(System.currentTimeMillis()));
            deadLetterEntry.put("originalStream", message.getStream());
            deadLetterEntry.put("originalId", message.getId().getValue());
            
            // Add dead letter entry
            streamRedisTemplate.opsForStream().add(
                StreamRecords.newRecord().in(deadLetterStream)
                    .ofMap(deadLetterEntry)
            );
            logger.info("[RedisStreamEventBus] Moved message to dead letter queue: ID={}, DeadLetterStream={}", 
                       message.getId(), deadLetterStream);
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error moving message to dead letter queue: ID={}, Error: {}", 
                       message.getId(), e.getMessage(), e);
        }
    }
    
    /**
     * Deserializes a JSON string to a DomainEvent.
     *
     * @param json The JSON string to deserialize
     * @param eventClass The specific DomainEvent class to deserialize to
     * @return The deserialized DomainEvent, or null if deserialization is not possible
     */
    private DomainEvent deserializeDomainEvent(String json, Class<? extends DomainEvent> eventClass) {
        try {
            // Use the shared optimized ObjectMapper for deserialization
            // Since we now store plain JSON strings in Redis (not wrapped in arrays),
            // direct deserialization should work in most cases.
            return objectMapper.readValue(json, eventClass);
        } catch (InvalidDefinitionException e) {
            // Expected case: Domain events often don't have default constructors
            logger.warn("[RedisStreamEventBus] Cannot deserialize {} due to missing constructor. This is normal if the event has been handled locally.", eventClass.getName());
            return null;
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error deserializing event: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Publishes event to local handlers with idempotency check.
     * This ensures each event is processed only once, even if it's received multiple times.
     *
     * @param event Domain event to publish
     */
    private void publishToLocalHandlers(DomainEvent event) throws Exception {
        List<EventHandler> eventHandlers = handlers.get(event.getClass());
        if (eventHandlers != null && event != null) {
            logger.info("[RedisStreamEventBus] Preparing to publish to local handlers, eventType={}, eventId={}, handlers={}", 
                    event.getClass().getName(), event.getEventId(), eventHandlers.size());
            
            String eventId = event.getEventId();
            
            // DomainEventContext 中没有 setStreamConsumer 方法，删除该行代码
            // Flag to track if any handler failed
            boolean anyHandlerFailed = false;
            
            for (EventHandler handler : eventHandlers) {
                String handlerName = handler.getClass().getName();
                // Generate a unique ID for this specific handler execution
                String handlerEventId = eventId + "::" + handlerName;
                
                try {
                    // Check if this specific handler has already successfully processed this event
                    if (idempotencyProperties.isEnabled() && eventId != null) {
                        IdempotencyService.ProcessingStatus handlerStatus = idempotencyService.getStatus(handlerEventId);
                        if (handlerStatus == IdempotencyService.ProcessingStatus.SUCCESS) {
                            logger.info("[RedisStreamEventBus] Event already processed by handler {}, skipping: eventId={}", 
                                    handlerName, eventId);
                            continue;
                        }
                    }
                    
                    logger.info("[RedisStreamEventBus] Invoking local handler: {}, eventId={}, eventType={}", 
                            handlerName, eventId, event.getClass().getName());
                    handler.handle(event);
                    
                    // Mark this specific handler as successful
                    if (idempotencyProperties.isEnabled() && eventId != null) {
                        idempotencyService.markAsSuccess(handlerEventId, new HashMap<>());
                    }
                } catch (Exception e) {
                    logger.error("[RedisStreamEventBus] Error handling event by local handler: {}", 
                            handlerName, e);
                    // Mark as failed if idempotency is enabled - for this specific handler
                    if (idempotencyProperties.isEnabled() && eventId != null) {
                        idempotencyService.markAsFailed(handlerEventId, e.getMessage());
                    }
                    // Don't rethrow immediately, continue to other handlers but mark as failed
                    anyHandlerFailed = true;
                }
            }
            // DomainEventContext 中没有 setStreamConsumer 方法，删除该行代码
            
            if (anyHandlerFailed) {
                // If any handler failed, throw exception to trigger Redis Stream retry
                // But successfully executed handlers won't run again due to idempotency check
                throw new RuntimeException("One or more handlers failed to process event " + eventId);
            }
            
            // Mark as success if idempotency is enabled - for the overall event
            // Only if ALL handlers succeeded
            if (idempotencyProperties.isEnabled() && eventId != null) {
                idempotencyService.markAsSuccess(eventId, new HashMap<>());
            }
            logger.info("[RedisStreamEventBus] Completed publish to local handlers, eventId={}", eventId);
        }
    }
    
    @Override
    public void publish(DomainEvent event) throws BaseException {
        try {
            // Capture context
            final CommandContext context = CommandContextHolder.getContext();
            
            // Define the publish action
            Runnable publishAction = () -> {
                try {
                    logger.info("[RedisStreamEventBus] Publishing event: {} to stream: {}, eventId: {}", 
                               event.getClass().getName(), streamKey, event.getEventId());
                    
                    // Create stream entry with field-value pairs
                    Map<String, String> entry = new HashMap<>(3);
                    
                    // Serialize event
                    String eventJson = objectMapper.writeValueAsString(event);
                    entry.put("event", eventJson);
                    entry.put("type", event.getClass().getName());
                    
                    // Serialize context if available
                    if (context != null) {
                        try {
                            String contextJson = objectMapper.writeValueAsString(context);
                            entry.put("context", contextJson);
                        } catch (Exception e) {
                            logger.warn("[RedisStreamEventBus] Failed to serialize context", e);
                        }
                    }
                    
                    logger.debug("[RedisStreamEventBus] Stream entry created: {}", entry);
                    
                    StreamOperations<String, String, String> streamOps = streamRedisTemplate.opsForStream();
                    Object recordId = streamOps.add(streamKey, entry);
                    
                    logger.info("[RedisStreamEventBus] Event published to stream: {}, recordId: {}, eventId: {}", 
                               event.getClass().getName(), recordId, event.getEventId());
                } catch (Exception e) {
                    logger.error("[RedisStreamEventBus] Error publishing event in action: {}", e.getMessage(), e);
                    throw new RuntimeException("Failed to publish event to Redis Stream", e);
                }
            };

            // Check if transaction is active
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                logger.info("[RedisStreamEventBus] Transaction active, registering synchronization for event: {}", event.getEventId());
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            publishAction.run();
                        } catch (Exception e) {
                            logger.error("[RedisStreamEventBus] Error publishing event after commit: {}", e.getMessage(), e);
                        }
                    }
                });
            } else {
                publishAction.run();
            }
            
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error publishing event: {}", e.getMessage(), e);
            throw new BaseException(BaseErrorCode.SYSTEM_ERROR.getCode(), "Failed to publish event to Redis Stream", e);
        }
    }
    
    @Override
    public void subscribe(Class<? extends DomainEvent> eventType, EventHandler handler) throws BaseException {
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
        eventTypeToClassMap.put(eventType.getName(), eventType);
        logger.info("[RedisStreamEventBus] Subscribed handler for event: {}", eventType.getName());
    }
    
    @Override
    public void unsubscribe(Class<? extends DomainEvent> eventType, EventHandler handler) throws BaseException {
        List<EventHandler> eventHandlers = handlers.get(eventType);
        if (eventHandlers != null) {
            eventHandlers.remove(handler);
            logger.info("[RedisStreamEventBus] Unsubscribed handler for event: {}", eventType.getName());
        }
    }
    
    /**
     * Scheduled task to clean up expired idempotency status records.
     * Runs every hour by default.
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanupExpiredIdempotencyStatus() {
        if (idempotencyProperties.isEnabled()) {
            try {
                logger.info("[RedisStreamEventBus] Starting cleanup of expired idempotency status records");
                idempotencyService.cleanupExpiredStatus();
                logger.info("[RedisStreamEventBus] Completed cleanup of expired idempotency status records");
            } catch (Exception e) {
                logger.error("[RedisStreamEventBus] Error during idempotency status cleanup: {}", e.getMessage(), e);
            }
        }
    }
}
