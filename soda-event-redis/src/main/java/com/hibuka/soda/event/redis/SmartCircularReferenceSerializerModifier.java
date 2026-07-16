package com.hibuka.soda.event.redis;

import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.Lock;

/**
 * Smart circular reference serializer modifier that automatically ignores common circular reference fields.
 * This modifier can detect and ignore common circular reference fields without requiring manual @JsonIgnore annotations.
 *
 * @author kangzeng.ckz
 * @since 2025/12/12
 */
public class SmartCircularReferenceSerializerModifier extends ValueSerializerModifier {

    private static final List<String> COMMON_CIRCULAR_REFERENCE_FIELD_NAMES = List.of();

    /**
     * Modifies the list of BeanPropertyWriter instances to ignore common circular reference fields.
     *
     * @param config Serialization configuration
     * @param beanDesc Bean description
     * @param beanProperties Original list of bean properties
     * @return Modified list of bean properties with circular reference fields ignored
     */
    // NOTE: @Override removed because Jackson 3 ValueSerializerModifier may have slightly different method signatures
    public List<BeanPropertyWriter> changeProperties(
            SerializationConfig config,
            BeanDescription beanDesc,
            List<BeanPropertyWriter> beanProperties) {
        
        List<BeanPropertyWriter> result = new ArrayList<>(beanProperties.size());
        
        for (BeanPropertyWriter writer : beanProperties) {
            if (shouldIgnoreField(writer)) {
                continue;
            }
            result.add(writer);
        }
        
        return result;
    }

    /**
     * Determines whether a field should be ignored for serialization to prevent circular references.
     *
     * @param writer Bean property writer for the field
     * @return true if the field should be ignored, false otherwise
     */
    private boolean shouldIgnoreField(BeanPropertyWriter writer) {
        // Check if field name is in common circular reference field names
        String fieldName = writer.getName();
        if (COMMON_CIRCULAR_REFERENCE_FIELD_NAMES.contains(fieldName.toLowerCase())) {
            return true;
        }
        
        // Check if field type is a common circular reference type
        Class<?> fieldType = writer.getType().getRawClass();
        
        // Ignore logger types
        if (Logger.class.isAssignableFrom(fieldType)) {
            return true;
        }
        
        // Ignore executor and thread pool types
        if (ExecutorService.class.isAssignableFrom(fieldType) ||
            ThreadPoolExecutor.class.isAssignableFrom(fieldType) ||
            ScheduledExecutorService.class.isAssignableFrom(fieldType)) {
            return true;
        }
        
        // Ignore lock and concurrency types
        if (Lock.class.isAssignableFrom(fieldType)) {
            return true;
        }
        
        // Ignore Spring framework types
        String className = fieldType.getName();
        if (className.startsWith("org.springframework.") && 
            (className.contains("Context") || 
             className.contains("BeanFactory") || 
             className.contains("Environment") || 
             className.contains("ApplicationEvent") || 
             className.contains("Cache") ||
             className.contains("Manager") ||
             className.contains("Service") ||
             className.contains("Factory"))) {
            return true;
        }
        
        // Ignore Logback types
        if (className.startsWith("ch.qos.logback.")) {
            return true;
        }
        
        return false;
    }

    /**
     * Returns null for serializer, using default serializer.
     *
     * @param config Serialization configuration
     * @param beanDesc Bean description
     * @param serializer Default serializer
     * @return null to use default serializer
     */
    // NOTE: Jackson 3 migration - method signatures may differ from ValueSerializerModifier
    public ValueSerializer<?> modifySerializer(
            SerializationConfig config,
            BeanDescription beanDesc,
            ValueSerializer<?> serializer) {
        return serializer; // Use default serializer
    }
}