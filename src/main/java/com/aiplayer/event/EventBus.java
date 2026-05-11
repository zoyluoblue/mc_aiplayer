package com.aiplayer.event;

import java.util.function.Consumer;

public interface EventBus {

        <T> Subscription subscribe(Class<T> eventType, Consumer<T> subscriber);

        <T> Subscription subscribe(Class<T> eventType, Consumer<T> subscriber, int priority);

        <T> void publish(T event);

        <T> void publishAsync(T event);

        void unsubscribeAll(Class<?> eventType);

        void clear();

        int getSubscriberCount(Class<?> eventType);

        interface Subscription {
                void unsubscribe();

                boolean isActive();
    }
}
