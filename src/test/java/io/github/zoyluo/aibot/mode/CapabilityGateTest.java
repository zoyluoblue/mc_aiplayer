package io.github.zoyluo.aibot.mode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityGateTest {
    @Test
    void deniedOperationNeverExecutesAndEmitsOneStructuredDecision() {
        AtomicInteger executions = new AtomicInteger();
        List<CapabilityDecision> decisions = new ArrayList<>();
        CapabilityGate gate = new CapabilityGate(
                OperatingProfile.STRICT_SURVIVAL,
                OperatorCapabilities.defaults(),
                decisions::add);

        CapabilityGate.Execution<Integer> result = gate.call(
                PrivilegedCapability.EMERGENCY_TELEPORT,
                executions::incrementAndGet);

        assertFalse(result.executed());
        assertNull(result.value());
        assertEquals(0, executions.get());
        assertEquals(1, decisions.size());
        assertEquals(result.decision(), decisions.get(0));
        assertFalse(result.decision().allowed());
        assertEquals(OperatingProfile.STRICT_SURVIVAL, result.decision().profile());
        assertEquals(PrivilegedCapability.EMERGENCY_TELEPORT, result.decision().capability());
    }

    @Test
    void allowedOperatorOperationExecutesOnceAndReturnsItsValue() {
        AtomicInteger executions = new AtomicInteger();
        List<CapabilityDecision> decisions = new ArrayList<>();
        CapabilityGate gate = new CapabilityGate(
                OperatingProfile.OPERATOR,
                new OperatorCapabilities(false, false, true, false),
                decisions::add);

        CapabilityGate.Execution<Integer> result = gate.call(
                PrivilegedCapability.FORCED_PICKUP,
                executions::incrementAndGet);

        assertTrue(result.executed());
        assertEquals(1, result.value());
        assertEquals(1, executions.get());
        assertEquals(1, decisions.size());
        assertTrue(decisions.get(0).allowed());
    }
}
