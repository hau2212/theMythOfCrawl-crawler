package com.mythofcrawl.crawler.core.sync;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Pure-Java 0ms in-process push event bus with {@link AutoCloseable} subscription tokens
 * so floating dialogs and panels never leak listeners across dispose/unload cycles.
 */
public final class InstantEventBus {

    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private final Map<Class<?>, CopyOnWriteArrayList<Consumer<Object>>> typedListeners = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<Object>>> topicListeners = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> Subscription subscribe(Class<T> eventType, Consumer<T> listener) {
        if (eventType == null || listener == null) {
            return () -> {};
        }
        Consumer<Object> adapter = obj -> {
            if (eventType.isInstance(obj)) {
                listener.accept((T) obj);
            }
        };
        CopyOnWriteArrayList<Consumer<Object>> list =
            typedListeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
        list.add(adapter);
        return new OnceSubscription(() -> list.remove(adapter));
    }

    public Subscription subscribeTopic(String topic, Consumer<Object> listener) {
        if (topic == null || topic.isEmpty() || listener == null) {
            return () -> {};
        }
        CopyOnWriteArrayList<Consumer<Object>> list =
            topicListeners.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return new OnceSubscription(() -> list.remove(listener));
    }

    public void publish(Object event) {
        if (event == null) {
            return;
        }
        for (Map.Entry<Class<?>, CopyOnWriteArrayList<Consumer<Object>>> entry : typedListeners.entrySet()) {
            if (entry.getKey().isInstance(event)) {
                dispatch(entry.getValue(), event);
            }
        }
    }

    public void publishTopic(String topic, Object payload) {
        if (topic == null || topic.isEmpty()) {
            return;
        }
        List<Consumer<Object>> list = topicListeners.get(topic);
        if (list != null) {
            dispatch(list, payload);
        }
    }

    public int listenerCount() {
        int count = 0;
        for (CopyOnWriteArrayList<Consumer<Object>> l : typedListeners.values()) {
            count += l.size();
        }
        for (CopyOnWriteArrayList<Consumer<Object>> l : topicListeners.values()) {
            count += l.size();
        }
        return count;
    }

    public void clear() {
        typedListeners.clear();
        topicListeners.clear();
    }

    private static void dispatch(List<Consumer<Object>> listeners, Object payload) {
        for (Consumer<Object> listener : listeners) {
            try {
                listener.accept(payload);
            } catch (Exception ignored) {
            }
        }
    }

    private static final class OnceSubscription implements Subscription {
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Runnable onClose;

        OnceSubscription(Runnable onClose) {
            this.onClose = onClose;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                onClose.run();
            }
        }
    }
}
