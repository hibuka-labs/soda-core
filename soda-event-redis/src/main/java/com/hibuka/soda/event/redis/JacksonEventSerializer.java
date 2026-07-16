package com.hibuka.soda.event.redis;

import com.hibuka.soda.event.EventSerializer;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;

/**
 * Jackson-based {@link EventSerializer} that resolves the ObjectMapper
 * from Spring context on first use. Works with both Jackson 2 (Spring Boot 3)
 * and Jackson 3 (Spring Boot 4) without compile-time Jackson dependency.
 *
 * @author soda
 * @since 17.0
 */
public class JacksonEventSerializer implements EventSerializer {

    private final ApplicationContext applicationContext;
    private volatile Object objectMapper;
    private volatile Method writeValueAsString;
    private volatile Method readValue;

    public JacksonEventSerializer(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    private Object getObjectMapper() {
        if (objectMapper == null) {
            synchronized (this) {
                if (objectMapper == null) {
                    objectMapper = resolveObjectMapper();
                    try {
                        this.writeValueAsString = objectMapper.getClass().getMethod("writeValueAsString", Object.class);
                        this.readValue = objectMapper.getClass().getMethod("readValue", String.class, Class.class);
                    } catch (NoSuchMethodException e) {
                        throw new SerializationException("ObjectMapper missing required methods", e);
                    }
                }
            }
        }
        return objectMapper;
    }

    private Object resolveObjectMapper() {
        String[] names = {"jacksonObjectMapper", "jacksonJsonMapper", "jsonMapper", "objectMapper"};
        for (String name : names) {
            try {
                if (applicationContext.containsBean(name)) {
                    return applicationContext.getBean(name);
                }
            } catch (Exception ignored) {}
        }
        // Fallback: scan by class name
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            try {
                Object bean = applicationContext.getBean(beanName);
                String className = bean.getClass().getName();
                if (className.contains("ObjectMapper") || className.contains("JsonMapper")) {
                    return bean;
                }
            } catch (Exception ignored) {}
        }
        throw new IllegalStateException("No ObjectMapper bean found. Ensure Jackson is on the classpath.");
    }

    @Override
    public String toJson(Object obj) {
        try {
            return (String) writeValueAsString.invoke(getObjectMapper(), obj);
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize event", e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T fromJson(String json, Class<T> type) {
        try {
            return (T) readValue.invoke(getObjectMapper(), json, type);
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize event", e);
        }
    }
}
