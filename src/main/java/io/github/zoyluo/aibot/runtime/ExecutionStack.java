package io.github.zoyluo.aibot.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Pure LIFO model used by TaskManager for nested safety preemption. */
public final class ExecutionStack<T> {
    private final Deque<Frame<T>> frames = new ArrayDeque<>();

    public Frame<T> push(T work, TaskOrigin origin) {
        Frame<T> frame = new Frame<>(UUID.randomUUID(), work, origin);
        frames.addLast(frame);
        return frame;
    }

    public Optional<Frame<T>> peek() {
        return Optional.ofNullable(frames.peekLast());
    }

    public Optional<Frame<T>> popResumable(boolean userPaused) {
        Frame<T> frame = frames.peekLast();
        if (frame == null || (userPaused && !frame.origin().safety())) {
            return Optional.empty();
        }
        return Optional.of(frames.removeLast());
    }

    public List<Frame<T>> drain() {
        List<Frame<T>> drained = new ArrayList<>();
        Frame<T> frame;
        while ((frame = frames.pollLast()) != null) {
            drained.add(frame);
        }
        return List.copyOf(drained);
    }

    public int size() {
        return frames.size();
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public record Frame<T>(UUID frameId, T work, TaskOrigin origin) {
    }
}
