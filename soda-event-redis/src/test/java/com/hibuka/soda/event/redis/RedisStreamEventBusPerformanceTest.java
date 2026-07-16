package com.hibuka.soda.event.redis;

import com.hibuka.soda.bus.configuration.EventProperties;
import com.hibuka.soda.cqrs.event.EventBus;
import com.hibuka.soda.cqrs.event.EventHandler;
import com.hibuka.soda.domain.event.AbstractDomainEvent;
import com.hibuka.soda.domain.event.DomainEvent;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance test for RedisStreamEventBus.
 * Tests the throughput and latency z Xof RedisStreamEventBus for publishing and consuming events.
 * This test requires a running Redis server to execute properly.
 */
@Disabled("This test requires a running Redis server")
@SpringBootTest(classes = RedisStreamEventBusPerformanceTest.TestApplication.class, 
        properties = {
                "soda.event.bus-type=redis",
                "soda.event.redis.stream.enabled=true",
                "soda.event.redis.stream.maxlen=10000",
                "soda.event.redis.stream.poll-timeout=100",
                "soda.event.redis.stream.batch-size=10",
                "soda.event.redis.stream.concurrency=2",
                "soda.event.redis.stream.idempotency.enabled=false"
        })
public class RedisStreamEventBusPerformanceTest {

    @Autowired
    private EventBus redisStreamEventBus;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String TEST_STREAM_KEY = "soda-test-event-stream";
    private static final int EVENT_COUNT = 1000;
    private static final int CONCURRENT_PUBLISHERS = 5;

    private TestEventHandler testEventHandler;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        testEventHandler = new TestEventHandler();
        executorService = Executors.newFixedThreadPool(CONCURRENT_PUBLISHERS + 2);
        // Clear test stream before each test
        stringRedisTemplate.delete(TEST_STREAM_KEY);
    }

    @AfterEach
    void tearDown() {
        testEventHandler.clearEvents();
        executorService.shutdown();
        // Clear test stream after each test
        stringRedisTemplate.delete(TEST_STREAM_KEY);
    }

    /**
     * Performance test for publishing events to Redis Stream.
     * Measures the throughput of event publishing with multiple concurrent publishers.
     * This test requires a running Redis server.
     */
    @Test
    @org.junit.jupiter.api.Disabled("This test requires a running Redis server")
    void testEventPublishingThroughput() throws Exception {
        // Subscribe to the event
        redisStreamEventBus.subscribe(TestDomainEvent.class, testEventHandler);

        // Create a latch to wait for all events to be processed
        CountDownLatch processingLatch = new CountDownLatch(EVENT_COUNT);
        testEventHandler.setCountDownLatch(processingLatch);

        // Measure time taken to publish events
        long startTime = System.nanoTime();

        // Create multiple concurrent publishers
        CountDownLatch publishingLatch = new CountDownLatch(CONCURRENT_PUBLISHERS);
        for (int i = 0; i < CONCURRENT_PUBLISHERS; i++) {
            final int publisherId = i;
            executorService.submit(() -> {
                try {
                    int eventsPerPublisher = EVENT_COUNT / CONCURRENT_PUBLISHERS;
                    for (int j = 0; j < eventsPerPublisher; j++) {
                        String eventId = "test-event-" + publisherId + "-" + j;
                        TestDomainEvent event = new TestDomainEvent(eventId, "test-data-" + j);
                        redisStreamEventBus.publish(event);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    publishingLatch.countDown();
                }
            });
        }

        // Wait for all publishers to finish
        publishingLatch.await();

        // Wait for all events to be processed
        boolean allProcessed = processingLatch.await(30, TimeUnit.SECONDS);
        assert allProcessed : "Not all events were processed within 30 seconds";

        long endTime = System.nanoTime();
        long totalTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

        // Calculate throughput
        double throughput = (double) EVENT_COUNT / (totalTimeMs / 1000.0);
        System.out.printf("\n=== Event Publishing Performance Results ===\n");
        System.out.printf("Total events: %d\n", EVENT_COUNT);
        System.out.printf("Concurrent publishers: %d\n", CONCURRENT_PUBLISHERS);
        System.out.printf("Total time: %d ms\n", totalTimeMs);
        System.out.printf("Throughput: %.2f events/second\n", throughput);
        System.out.printf("Average latency per event: %.2f ms\n", (double) totalTimeMs / EVENT_COUNT);
        System.out.printf("=========================================\n");

        // Verify all events were processed
        assert testEventHandler.getEvents().size() == EVENT_COUNT : 
                String.format("Expected %d events, but got %d", EVENT_COUNT, testEventHandler.getEvents().size());
    }

    /**
     * Performance test for event handling latency.
     * Measures the time taken from event publishing to event handling.
     * This test requires a running Redis server.
     */
    @Test
    @org.junit.jupiter.api.Disabled("This test requires a running Redis server")
    void testEventHandlingLatency() throws Exception {
        // Create a list to store latency measurements
        List<Long> latencies = new ArrayList<>();

        // Create a custom event handler to measure latency
        EventHandler<TestDomainEvent> latencyMeasuringHandler = event -> {
            if (event instanceof LatencyTestDomainEvent) {
                LatencyTestDomainEvent latencyEvent = (LatencyTestDomainEvent) event;
                long latency = System.nanoTime() - latencyEvent.getPublishTime();
                latencies.add(latency);
            }
        };

        // Subscribe to the event
        redisStreamEventBus.subscribe(LatencyTestDomainEvent.class, latencyMeasuringHandler);

        // Publish events and measure latency
        for (int i = 0; i < 100; i++) {
            LatencyTestDomainEvent event = new LatencyTestDomainEvent("latency-test-event-" + i, "test-data");
            redisStreamEventBus.publish(event);
            // Small delay between events to avoid overwhelming the system
            Thread.sleep(10);
        }

        // Wait for all events to be processed
        Thread.sleep(5000);

        // Calculate latency statistics
        if (!latencies.isEmpty()) {
            long totalLatency = latencies.stream().mapToLong(l -> l).sum();
            long avgLatency = totalLatency / latencies.size();
            long minLatency = latencies.stream().mapToLong(l -> l).min().orElse(0);
            long maxLatency = latencies.stream().mapToLong(l -> l).max().orElse(0);

            System.out.printf("\n=== Event Handling Latency Results ===\n");
            System.out.printf("Total events measured: %d\n", latencies.size());
            System.out.printf("Average latency: %.2f ms\n", TimeUnit.NANOSECONDS.toMillis(avgLatency));
            System.out.printf("Minimum latency: %.2f ms\n", TimeUnit.NANOSECONDS.toMillis(minLatency));
            System.out.printf("Maximum latency: %.2f ms\n", TimeUnit.NANOSECONDS.toMillis(maxLatency));
            System.out.printf("======================================\n");
        }

        assert !latencies.isEmpty() : "No latency measurements collected";
    }

    /**
     * Test application class for Spring Boot test.
     */
    @SpringBootApplication
    @ComponentScan(basePackages = "com.hibuka.soda")
    @Import(RedisEventBusAutoConfiguration.class)
    static class TestApplication {
        // This class is used to configure the Spring Boot test application context
    }

    /**
     * Test domain event class with latency measurement support.
     */
    static class TestDomainEvent extends AbstractDomainEvent {
        private String data;

        public TestDomainEvent(String eventId, String data) {
            this.data = data;
        }

        public String getData() {
            return data;
        }

        @Override
        public String getEventType() {
            return "TEST_EVENT";
        }
    }

    /**
     * Test domain event class for latency measurement.
     */
    static class LatencyTestDomainEvent extends TestDomainEvent {
        private final long publishTime;

        public LatencyTestDomainEvent(String eventId, String data) {
            super(eventId, data);
            this.publishTime = System.nanoTime();
        }

        public long getPublishTime() {
            return publishTime;
        }
    }

    /**
     * Test event handler class that collects received events and supports countdown latch.
     */
    static class TestEventHandler implements EventHandler<TestDomainEvent> {
        private final List<TestDomainEvent> events = new ArrayList<>();
        private CountDownLatch countDownLatch;

        @Override
        public void handle(TestDomainEvent event) {
            events.add(event);
            if (countDownLatch != null) {
                countDownLatch.countDown();
            }
        }

        public List<TestDomainEvent> getEvents() {
            return events;
        }

        public void clearEvents() {
            events.clear();
        }

        public void setCountDownLatch(CountDownLatch countDownLatch) {
            this.countDownLatch = countDownLatch;
        }
    }
}
