package io.github.zoyluo.aibot.task;

import java.util.Map;

/**
 * Task-level durable state owned by a Mission.
 *
 * <p>The Goal runtime persists this map together with the Mission record. Implementations must
 * only expose deterministic, compact values that are safe to restore on a later JVM. Transient
 * handles such as path executors, entity references and in-flight block breaking are deliberately
 * excluded; those are rebuilt from the restored world state.</p>
 */
public interface CheckpointableTask {
    Map<String, String> checkpoint();
}
