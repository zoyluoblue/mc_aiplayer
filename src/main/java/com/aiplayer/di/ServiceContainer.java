package com.aiplayer.di;

import java.util.Optional;

public interface ServiceContainer {

        <T> void register(Class<T> serviceType, T instance);

        <T> void register(String name, T instance);

        <T> T getService(Class<T> serviceType);

        <T> T getService(String name, Class<T> type);

        <T> Optional<T> findService(Class<T> serviceType);

        <T> Optional<T> findService(String name, Class<T> type);

        boolean hasService(Class<?> serviceType);

        boolean hasService(String name);

        boolean unregister(Class<?> serviceType);

        boolean unregister(String name);

        void clear();

        int getServiceCount();

        class ServiceNotFoundException extends RuntimeException {
        public ServiceNotFoundException(String message) {
            super(message);
        }

        public ServiceNotFoundException(Class<?> serviceType) {
            super("Service not found: " + serviceType.getName());
        }

        public ServiceNotFoundException(String name, Class<?> type) {
            super("Service not found: " + name + " (type: " + type.getName() + ")");
        }
    }
}
