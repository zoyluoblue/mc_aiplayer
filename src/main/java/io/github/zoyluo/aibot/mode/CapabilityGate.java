package io.github.zoyluo.aibot.mode;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Executes a privileged operation only after policy allows it and emits one structured decision. */
public final class CapabilityGate {
    private final OperatingProfile profile;
    private final OperatorCapabilities operatorCapabilities;
    private final Consumer<CapabilityDecision> decisionSink;

    public CapabilityGate(OperatingProfile profile,
                          OperatorCapabilities operatorCapabilities,
                          Consumer<CapabilityDecision> decisionSink) {
        this.profile = profile == null ? OperatingProfile.STRICT_SURVIVAL : profile;
        this.operatorCapabilities = operatorCapabilities;
        this.decisionSink = decisionSink == null ? ignored -> { } : decisionSink;
    }

    public CapabilityDecision execute(PrivilegedCapability capability, Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        return call(capability, () -> {
            operation.run();
            return null;
        }).decision();
    }

    public <T> Execution<T> call(PrivilegedCapability capability, Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        CapabilityDecision decision = CapabilityPolicy.decide(profile, operatorCapabilities, capability);
        decisionSink.accept(decision);
        if (!decision.allowed()) {
            return new Execution<>(decision, false, null);
        }
        return new Execution<>(decision, true, operation.get());
    }

    public record Execution<T>(CapabilityDecision decision, boolean executed, T value) {
    }
}
