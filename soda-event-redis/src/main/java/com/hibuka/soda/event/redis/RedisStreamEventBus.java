package com.hibuka.soda.event.redis;

import com.hibuka.soda.bus.configuration.EventProperties;
import com.hibuka.soda.context.CommandContext;
import com.hibuka.soda.context.CommandContextHolder;
import com.hibuka.soda.cqrs.event.EventBus;
import com.hibuka.soda.cqrs.event.EventHandler;
import com.hibuka.soda.domain.event.DomainEvent;
import com.hibuka.soda.event.EventSerializer;
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
    private final EventSerializer serializer;
    private final IdempotencyService idempotencyService;

    private StreamMessageListenerContainer<?, ?> container;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

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
        this.serializer = builder.serializer;
        this.idempotencyService = new RedisIdempotencyServiceImpl(redisTemplate, idempotencyProperties);
        logger.info("[RedisStreamEventBus] Registering {} event handlers, instance: {}", eventHandlers.size(), this.hashCode());
        registerEventHandlers(eventHandlers);
        logger.info("[RedisStreamEventBus] Constructor completed, instance: {}", this.hashCode());
    }

    public static class Builder {
        private final RedisTemplate<String, Object> redisTemplate;
        private final ApplicationEventPublisher applicationEventPublisher;
        private final RedisConnectionFactory redisConnectionFactory;
        private final List<EventHandler<? extends DomainEvent>> eventHandlers;
        private final String streamKey;
        private final EventSerializer serializer;

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

        public Builder(RedisTemplate<String, Object> redisTemplate,
                      ApplicationEventPublisher applicationEventPublisher,
                      RedisConnectionFactory redisConnectionFactory,
                      List<EventHandler<? extends DomainEvent>> eventHandlers,
                      String streamKey,
                      EventSerializer serializer) {
            this.redisTemplate = redisTemplate;
            this.applicationEventPublisher = applicationEventPublisher;
            this.redisConnectionFactory = redisConnectionFactory;
            this.eventHandlers = eventHandlers;
            this.streamKey = streamKey;
            this.serializer = serializer;
        }

        public Builder groupName(String groupName) { this.groupName = groupName; return this; }
        public Builder consumerName(String consumerName) { this.consumerName = consumerName; return this; }
        public Builder maxlen(long maxlen) { this.maxlen = maxlen; return this; }
        public Builder pollTimeout(long pollTimeout) { this.pollTimeout = pollTimeout; return this; }
        public Builder batchSize(int batchSize) { this.batchSize = batchSize; return this; }
        public Builder concurrency(int concurrency) { this.concurrency = concurrency; return this; }
        public Builder maxRetries(int maxRetries) { this.maxRetries = maxRetries; return this; }
        public Builder initialRetryDelay(long initialRetryDelay) { this.initialRetryDelay = initialRetryDelay; return this; }
        public Builder exponentialBackoff(boolean exponentialBackoff) { this.exponentialBackoff = exponentialBackoff; return this; }
        public Builder deadLetterStream(String deadLetterStream) { this.deadLetterStream = deadLetterStream; return this; }
        public Builder idempotencyProperties(EventProperties.RedisProperties.StreamProperties.IdempotencyProperties idempotencyProperties) {
            this.idempotencyProperties = idempotencyProperties; return this;
        }

        public RedisStreamEventBus build() {
            return new RedisStreamEventBus(this);
        }
    }

    private void registerEventHandlers(List<EventHandler<? extends DomainEvent>> eventHandlers) {
        for (EventHandler<? extends DomainEvent> handler : eventHandlers) {
            Class<?>[] typeArguments = GenericTypeResolver.resolveTypeArguments(handler.getClass(), EventHandler.class);
            if (typeArguments != null && typeArguments.length > 0) {
                @SuppressWarnings("unchecked")
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
    public void afterPropertiesSet() {
        if (initialized.compareAndSet(false, true)) {
            logger.info("[RedisStreamEventBus] Initializing Redis Stream event bus");
            createStreamAndGroup();
            configureStreamListener();
            logger.info("[RedisStreamEventBus] Initialized Redis Stream event bus");
        }
    }

    private void createStreamAndGroup() {
        try {
            Boolean exists = streamRedisTemplate.hasKey(streamKey);
            if (exists == null || !exists) {
                Map<String, String> initialEntry = new HashMap<>();
                initialEntry.put("type", "INIT");
                streamRedisTemplate.opsForStream().add(streamKey, initialEntry);
            }
            try {
                streamRedisTemplate.opsForStream().createGroup(streamKey, groupName);
                logger.info("[RedisStreamEventBus] Consumer group created: {}", groupName);
            } catch (Exception e) {
                logger.info("[RedisStreamEventBus] Consumer group likely already exists: {}", groupName);
            }
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error creating stream or consumer group: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Redis Stream event bus", e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void configureStreamListener() {
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofMillis(pollTimeout))
                        .batchSize(batchSize)
                        .keySerializer(new org.springframework.data.redis.serializer.StringRedisSerializer())
                        .hashKeySerializer(new org.springframework.data.redis.serializer.StringRedisSerializer())
                        .hashValueSerializer(new org.springframework.data.redis.serializer.StringRedisSerializer())
                        .errorHandler(throwable ->
                                logger.warn("[RedisStreamEventBus] Stream listener error, will retry: {}", throwable.getMessage()))
                        .build();

        StreamMessageListenerContainer container = StreamMessageListenerContainer.create(redisConnectionFactory, options);
        this.container = container;

        StreamListener<String, MapRecord<String, String, String>> listener = message -> {
            try {
                handleStreamMessageWithRetry(message);
            } catch (Exception e) {
                logger.error("[RedisStreamEventBus] Error handling stream message: {}", e.getMessage(), e);
            }
        };

        ReadOffset offset = ReadOffset.lastConsumed();
        StreamOffset<String> streamOffset = StreamOffset.create(streamKey, offset);

        for (int i = 0; i < concurrency; i++) {
            String currentConsumerName = concurrency > 1 ? consumerName + "-" + i : consumerName;
            Consumer consumer = Consumer.from(groupName, currentConsumerName);
            Subscription sub = container.receive(consumer, streamOffset, listener);
            this.subscriptions.add(sub);
            logger.info("[RedisStreamEventBus] Created consumer subscription: {}", currentConsumerName);
        }

        container.start();
        logger.info("[RedisStreamEventBus] Stream listener container started with options: pollTimeout={}, batchSize={}, concurrency={}",
                   pollTimeout, batchSize, concurrency);
    }

    private void handleStreamMessageWithRetry(MapRecord<String, String, String> message) {
        boolean processed = false;
        String eventId = null;

        try {
            eventId = extractEventIdFromMessage(message);

            if (idempotencyProperties.isEnabled() && eventId != null) {
                IdempotencyService.ProcessingStatus status = idempotencyService.getStatus(eventId);
                if (status == IdempotencyService.ProcessingStatus.SUCCESS) {
                    streamRedisTemplate.opsForStream().acknowledge(streamKey, groupName, message.getId());
                    return;
                } else if (status == IdempotencyService.ProcessingStatus.PROCESSING) {
                    return;
                }
            }

            for (int retryCount = 0; retryCount <= maxRetries; retryCount++) {
                try {
                    boolean canProcess = true;
                    if (idempotencyProperties.isEnabled() && eventId != null) {
                        canProcess = idempotencyService.beginProcessing(eventId);
                    }
                    if (canProcess) {
                        processed = handleStreamMessageInternal(message);
                        if (processed) {
                            if (idempotencyProperties.isEnabled() && eventId != null) {
                                idempotencyService.markAsSuccess(eventId, new HashMap<>());
                            }
                            streamRedisTemplate.opsForStream().acknowledge(streamKey, groupName, message.getId());
                            break;
                        } else {
                            if (idempotencyProperties.isEnabled() && eventId != null) {
                                idempotencyService.markAsFailed(eventId, "Processing returned false");
                            }
                        }
                    } else {
                        streamRedisTemplate.opsForStream().acknowledge(streamKey, groupName, message.getId());
                        break;
                    }
                } catch (Exception e) {
                    if (idempotencyProperties.isEnabled() && eventId != null) {
                        idempotencyService.markAsFailed(eventId, e.getMessage());
                    }
                    if (retryCount < maxRetries) {
                        long delay = calculateRetryDelay(retryCount);
                        logger.warn("[RedisStreamEventBus] Failed to process message, retrying in {}ms (attempt {}/{}): ID={}, Error: {}",
                                   delay, retryCount + 1, maxRetries + 1, message.getId(), e.getMessage());
                        try { Thread.sleep(delay); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    } else {
                        logger.error("[RedisStreamEventBus] Maximum retries exceeded, moving to dead letter queue: ID={}", message.getId());
                        moveToDeadLetterQueue(message, "Max retries exceeded");
                        streamRedisTemplate.opsForStream().acknowledge(streamKey, groupName, message.getId());
                        return;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error in message handling flow: {}", e.getMessage(), e);
        }

        if (!processed) {
            moveToDeadLetterQueue(message, "Processing failed without exception");
            streamRedisTemplate.opsForStream().acknowledge(streamKey, groupName, message.getId());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractEventIdFromMessage(MapRecord<String, String, String> message) {
        try {
            String serializedEvent = message.getValue().get("event");
            if (serializedEvent == null) {
                return null;
            }
            // Deserialize as Map to extract eventId without full type resolution
            Map<String, Object> eventMap = serializer.fromJson(serializedEvent, Map.class);
            if (eventMap == null) {
                return null;
            }
            // Handle array format [eventType, eventData]
            Object eventData = eventMap;
            if (eventMap.containsKey("eventId")) {
                // Direct object format - check first to avoid array confusion
            } else if (eventMap.containsKey("0") && eventMap.containsKey("1")) {
                // Could be array format, check element 1
                Object element1 = eventMap.get("1");
                if (element1 instanceof Map) {
                    eventData = (Map<String, Object>) element1;
                }
            }
            if (eventData instanceof Map) {
                Object id = ((Map<String, Object>) eventData).get("eventId");
                if (id != null) {
                    return id.toString();
                }
            }
            // Fallback: try full deserialization
            DomainEvent event = extractEventFromMessage(message);
            if (event != null) {
                return event.getEventId();
            }
        } catch (Exception e) {
            logger.debug("[RedisStreamEventBus] Failed to extract eventId from message: {}", e.getMessage());
        }
        return null;
    }

    private DomainEvent extractEventFromMessage(MapRecord<String, String, String> message) {
        try {
            String serializedEvent = message.getValue().get("event");
            String eventType = message.getValue().get("type");
            if (serializedEvent == null || eventType == null) {
                return null;
            }
            Class<? extends DomainEvent> eventClass = eventTypeToClassMap.get(eventType);
            if (eventClass == null) {
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
            return deserializeDomainEvent(serializedEvent, eventClass);
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error extracting event from message: {}", e.getMessage(), e);
            return null;
        }
    }

    private boolean handleStreamMessageInternal(MapRecord<String, String, String> message) throws Exception {
        String contextJson = message.getValue().get("context");
        if (contextJson != null) {
            try {
                CommandContext context = serializer.fromJson(contextJson, CommandContext.class);
                CommandContextHolder.setContext(context);
            } catch (Exception e) {
                logger.warn("[RedisStreamEventBus] Failed to deserialize context", e);
            }
        }

        try {
            String serializedEvent = message.getValue().get("event");
            String eventType = message.getValue().get("type");

            if (serializedEvent == null || eventType == null) {
                logger.error("[RedisStreamEventBus] Missing 'event' or 'type' field");
                return false;
            }

            Class<? extends DomainEvent> eventClass = eventTypeToClassMap.get(eventType);
            if (eventClass == null) {
                String unquotedEventType = eventType;
                if ((eventType.startsWith("\"") && eventType.endsWith("\"")) ||
                    (eventType.startsWith("'") && eventType.endsWith("'"))) {
                    unquotedEventType = eventType.substring(1, eventType.length() - 1);
                    eventClass = eventTypeToClassMap.get(unquotedEventType);
                }
            }

            if (eventClass == null) {
                logger.error("[RedisStreamEventBus] No registered handler for event type: {}", eventType);
                return false;
            }

            DomainEvent event = deserializeDomainEvent(serializedEvent, eventClass);
            if (event != null) {
                publishToLocalHandlers(event);
            }
            return true;
        } finally {
            if (contextJson != null) {
                CommandContextHolder.clearContext();
            }
        }
    }

    private long calculateRetryDelay(int retryCount) {
        if (exponentialBackoff) {
            return (long) (initialRetryDelay * Math.pow(2, retryCount));
        }
        return initialRetryDelay;
    }

    private void moveToDeadLetterQueue(MapRecord<String, String, String> message, String reason) {
        try {
            Map<String, String> deadLetterEntry = new HashMap<>(message.getValue().size() + 4);
            deadLetterEntry.putAll(message.getValue());
            deadLetterEntry.put("deadLetterReason", reason);
            deadLetterEntry.put("deadLetterTimestamp", String.valueOf(System.currentTimeMillis()));
            deadLetterEntry.put("originalStream", message.getStream());
            deadLetterEntry.put("originalId", message.getId().getValue());
            streamRedisTemplate.opsForStream().add(
                StreamRecords.newRecord().in(deadLetterStream).ofMap(deadLetterEntry));
            logger.info("[RedisStreamEventBus] Moved message to dead letter queue: ID={}", message.getId());
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error moving message to dead letter queue: ID={}, Error: {}",
                       message.getId(), e.getMessage(), e);
        }
    }

    private DomainEvent deserializeDomainEvent(String json, Class<? extends DomainEvent> eventClass) {
        try {
            return serializer.fromJson(json, eventClass);
        } catch (EventSerializer.SerializationException e) {
            logger.warn("[RedisStreamEventBus] Cannot deserialize {}. This is normal if the event has been handled locally.", eventClass.getName());
            return null;
        } catch (Exception e) {
            logger.error("[RedisStreamEventBus] Error deserializing event: {}", e.getMessage(), e);
            return null;
        }
    }

    private void publishToLocalHandlers(DomainEvent event) throws Exception {
        List<EventHandler> eventHandlers = handlers.get(event.getClass());
        if (eventHandlers == null || event == null) return;

        String eventId = event.getEventId();
        boolean anyHandlerFailed = false;

        for (EventHandler handler : eventHandlers) {
            String handlerName = handler.getClass().getName();
            String handlerEventId = eventId + "::" + handlerName;

            try {
                if (idempotencyProperties.isEnabled() && eventId != null) {
                    IdempotencyService.ProcessingStatus handlerStatus = idempotencyService.getStatus(handlerEventId);
                    if (handlerStatus == IdempotencyService.ProcessingStatus.SUCCESS) {
                        logger.info("[RedisStreamEventBus] Event already processed by handler {}, skipping: eventId={}", handlerName, eventId);
                        continue;
                    }
                }
                handler.handle(event);
                if (idempotencyProperties.isEnabled() && eventId != null) {
                    idempotencyService.markAsSuccess(handlerEventId, new HashMap<>());
                }
            } catch (Exception e) {
                logger.error("[RedisStreamEventBus] Error handling event by local handler: {}", handlerName, e);
                if (idempotencyProperties.isEnabled() && eventId != null) {
                    idempotencyService.markAsFailed(handlerEventId, e.getMessage());
                }
                anyHandlerFailed = true;
            }
        }

        if (anyHandlerFailed) {
            throw new RuntimeException("One or more handlers failed to process event " + eventId);
        }
        if (idempotencyProperties.isEnabled() && eventId != null) {
            idempotencyService.markAsSuccess(eventId, new HashMap<>());
        }
    }

    @Override
    public void publish(DomainEvent event) throws BaseException {
        try {
            final CommandContext context = CommandContextHolder.getContext();

            Runnable publishAction = () -> {
                try {
                    Map<String, String> entry = new HashMap<>(3);
                    String eventJson = serializer.toJson(event);
                    entry.put("event", eventJson);
                    entry.put("type", event.getClass().getName());

                    if (context != null) {
                        try {
                            String contextJson = serializer.toJson(context);
                            entry.put("context", contextJson);
                        } catch (Exception e) {
                            logger.warn("[RedisStreamEventBus] Failed to serialize context", e);
                        }
                    }

                    StreamOperations<String, String, String> streamOps = streamRedisTemplate.opsForStream();
                    Object recordId = streamOps.add(streamKey, entry);
                    logger.info("[RedisStreamEventBus] Event published to stream: {}, recordId: {}, eventId: {}",
                               event.getClass().getName(), recordId, event.getEventId());
                } catch (Exception e) {
                    logger.error("[RedisStreamEventBus] Error publishing event in action: {}", e.getMessage(), e);
                    throw new RuntimeException("Failed to publish event to Redis Stream", e);
                }
            };

            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try { publishAction.run(); } catch (Exception e) {
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

    @Scheduled(fixedRate = 3600000)
    public void cleanupExpiredIdempotencyStatus() {
        if (idempotencyProperties.isEnabled()) {
            try {
                idempotencyService.cleanupExpiredStatus();
            } catch (Exception e) {
                logger.error("[RedisStreamEventBus] Error during idempotency status cleanup: {}", e.getMessage(), e);
            }
        }
    }
}
