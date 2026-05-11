package com.aiplayer.di;

import com.aiplayer.AiPlayerMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleServiceContainer implements ServiceContainer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleServiceContainer.class);

        private final ConcurrentHashMap<Class<?>, Object> typeRegistry;

        private final ConcurrentHashMap<String, Object> namedRegistry;

    public SimpleServiceContainer() {
        this.typeRegistry = new ConcurrentHashMap<>();
        this.namedRegistry = new ConcurrentHashMap<>();
    }

    @Override
    public <T> void register(Class<T> serviceType, T instance) {
        if (serviceType == null) {
            throw new IllegalArgumentException("Service type cannot be null");
        }
        if (instance == null) {
            throw new IllegalArgumentException("Service instance cannot be null");
        }

        Object previous = typeRegistry.put(serviceType, instance);
        if (previous != null) {
            LOGGER.debug("Replaced service for type: {}", serviceType.getSimpleName());
        } else {
            LOGGER.debug("Registered service for type: {}", serviceType.getSimpleName());
        }
    }

    @Override
    public <T> void register(String name, T instance) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Service name cannot be null or blank");
        }
        if (instance == null) {
            throw new IllegalArgumentException("Service instance cannot be null");
        }

        Object previous = namedRegistry.put(name, instance);
        if (previous != null) {
            LOGGER.debug("Replaced named service: {}", name);
        } else {
            LOGGER.debug("Registered named service: {}", name);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getService(Class<T> serviceType) {
        if (serviceType == null) {
            throw new IllegalArgumentException("Service type cannot be null");
        }

        Object service = typeRegistry.get(serviceType);
        if (service == null) {
            throw new ServiceNotFoundException(serviceType);
        }

        return (T) service;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getService(String name, Class<T> type) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Service name cannot be null or blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Service type cannot be null");
        }

        Object service = namedRegistry.get(name);
        if (service == null) {
            throw new ServiceNotFoundException(name, type);
        }

        if (!type.isInstance(service)) {
            throw new ClassCastException("Service '" + name + "' is not of type " + type.getName());
        }

        return (T) service;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> findService(Class<T> serviceType) {
        if (serviceType == null) {
            return Optional.empty();
        }

        Object service = typeRegistry.get(serviceType);
        return Optional.ofNullable((T) service);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> findService(String name, Class<T> type) {
        if (name == null || name.isBlank() || type == null) {
            return Optional.empty();
        }

        Object service = namedRegistry.get(name);
        if (service == null || !type.isInstance(service)) {
            return Optional.empty();
        }

        return Optional.of((T) service);
    }

    @Override
    public boolean hasService(Class<?> serviceType) {
        return serviceType != null && typeRegistry.containsKey(serviceType);
    }

    @Override
    public boolean hasService(String name) {
        return name != null && namedRegistry.containsKey(name);
    }

    @Override
    public boolean unregister(Class<?> serviceType) {
        if (serviceType == null) return false;

        Object removed = typeRegistry.remove(serviceType);
        if (removed != null) {
            LOGGER.debug("Unregistered service for type: {}", serviceType.getSimpleName());
            return true;
        }
        return false;
    }

    @Override
    public boolean unregister(String name) {
        if (name == null) return false;

        Object removed = namedRegistry.remove(name);
        if (removed != null) {
            LOGGER.debug("Unregistered named service: {}", name);
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        typeRegistry.clear();
        namedRegistry.clear();
        AiPlayerMod.info("service", "ServiceContainer cleared");
    }

    @Override
    public int getServiceCount() {
        return typeRegistry.size() + namedRegistry.size();
    }

        public String debugInfo() {
        StringBuilder sb = new StringBuilder("ServiceContainer {\n");
        sb.append("  Type Registry (").append(typeRegistry.size()).append(" services):\n");
        typeRegistry.forEach((type, instance) ->
            sb.append("    - ").append(type.getSimpleName())
              .append(" -> ").append(instance.getClass().getSimpleName()).append("\n"));

        sb.append("  Named Registry (").append(namedRegistry.size()).append(" services):\n");
        namedRegistry.forEach((name, instance) ->
            sb.append("    - ").append(name)
              .append(" -> ").append(instance.getClass().getSimpleName()).append("\n"));

        sb.append("}");
        return sb.toString();
    }
}
