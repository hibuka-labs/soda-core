package com.hibuka.soda.event.redis;

import tools.jackson.databind.ObjectMapper;
import com.hibuka.soda.bus.configuration.EventProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test cases for Redis event serialization circular reference handling.
 *
 * @author kangzeng.ckz
 * @since 2025-12-12
 */
class RedisEventSerializationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisEventBusAutoConfiguration.class));
    
    /**
     * Test that circular reference handling properties are loaded correctly.
     * This test verifies that the EventProperties correctly loads the circular reference handling configuration.
     */
    @Test
    void testCircularReferencePropertiesLoading() {
        contextRunner.withPropertyValues(
                "soda.event.bus-type=redis",
                "soda.event.serialization.circular-reference-handler=ERROR",
                "soda.event.serialization.fail-on-self-references=true"
        )
        .run(context -> {
            EventProperties properties = context.getBean(EventProperties.class);
            assertThat(properties).isNotNull();
            assertThat(properties.getSerialization()).isNotNull();
            assertThat(properties.getSerialization().getCircularReferenceHandler())
                .isEqualTo(EventProperties.SerializationProperties.CircularReferenceHandler.ERROR);
            assertThat(properties.getSerialization().isFailOnSelfReferences()).isTrue();
        });
    }
    
    /**
     * Test that circular reference handling properties have correct default values.
     * This test verifies that the EventProperties has correct default values for circular reference handling.
     */
    @Test
    void testCircularReferencePropertiesDefaultValues() {
        contextRunner.withPropertyValues(
                "soda.event.bus-type=redis"
        )
        .run(context -> {
            EventProperties properties = context.getBean(EventProperties.class);
            assertThat(properties).isNotNull();
            assertThat(properties.getSerialization()).isNotNull();
            assertThat(properties.getSerialization().getCircularReferenceHandler())
                .isEqualTo(EventProperties.SerializationProperties.CircularReferenceHandler.IGNORE);
            assertThat(properties.getSerialization().isFailOnSelfReferences()).isFalse();
        });
    }
    
    /**
     * Test IGNORE strategy property.
     */
    @Test
    void testCircularReferenceHandlingWithIgnoreStrategy() {
        contextRunner.withPropertyValues(
                "soda.event.bus-type=redis",
                "soda.event.serialization.circular-reference-handler=IGNORE"
        )
        .run(context -> {
            EventProperties properties = context.getBean(EventProperties.class);
            assertThat(properties).isNotNull();
            assertThat(properties.getSerialization().getCircularReferenceHandler())
                .isEqualTo(EventProperties.SerializationProperties.CircularReferenceHandler.IGNORE);
        });
    }
    
    /**
     * Test RETAIN strategy property.
     */
    @Test
    void testCircularReferenceHandlingWithRetainStrategy() {
        contextRunner.withPropertyValues(
                "soda.event.bus-type=redis",
                "soda.event.serialization.circular-reference-handler=RETAIN"
        )
        .run(context -> {
            EventProperties properties = context.getBean(EventProperties.class);
            assertThat(properties).isNotNull();
            assertThat(properties.getSerialization().getCircularReferenceHandler())
                .isEqualTo(EventProperties.SerializationProperties.CircularReferenceHandler.RETAIN);
        });
    }
}