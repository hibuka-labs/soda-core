package com.hibuka.soda.event;

/**
 * Event serializer abstraction.
 * <p>
 * Decouples event serialization from any specific JSON library.
 * Implementations may use Jackson, Gson, Kryo, or any other serialization mechanism.
 * </p>
 *
 * @author soda
 * @since 17.0
 */
public interface EventSerializer {

    /**
     * Serialize an object to a string.
     *
     * @param obj the object to serialize
     * @return the serialized string
     * @throws SerializationException if serialization fails
     */
    String toJson(Object obj);

    /**
     * Deserialize a string to an object of the given type.
     *
     * @param json the string to deserialize
     * @param type the target type
     * @param <T> the target type
     * @return the deserialized object
     * @throws SerializationException if deserialization fails
     */
    <T> T fromJson(String json, Class<T> type);

    /**
     * Exception thrown when serialization or deserialization fails.
     */
    class SerializationException extends RuntimeException {
        public SerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
