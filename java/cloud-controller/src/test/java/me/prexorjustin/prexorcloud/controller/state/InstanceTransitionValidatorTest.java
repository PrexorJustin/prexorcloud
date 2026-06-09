package me.prexorjustin.prexorcloud.controller.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.prexorjustin.prexorcloud.protocol.InstanceState;

import org.junit.jupiter.api.Test;

class InstanceTransitionValidatorTest {

    @Test
    void allowsExpectedStartupAndDrainTransitions() {
        assertTrue(InstanceTransitionValidator.isValid(InstanceState.SCHEDULED, InstanceState.STARTING));
        assertTrue(InstanceTransitionValidator.isValid(InstanceState.PREPARING, InstanceState.SCHEDULED));
        assertTrue(InstanceTransitionValidator.isValid(InstanceState.STARTING, InstanceState.SCHEDULED));
        assertTrue(InstanceTransitionValidator.isValid(InstanceState.STARTING, InstanceState.RUNNING));
        assertTrue(InstanceTransitionValidator.isValid(InstanceState.RUNNING, InstanceState.DRAINING));
        assertTrue(InstanceTransitionValidator.isValid(InstanceState.DRAINING, InstanceState.STOPPING));
    }

    @Test
    void rejectsReactivatingTerminalInstances() {
        assertFalse(InstanceTransitionValidator.isValid(InstanceState.STOPPED, InstanceState.RUNNING));
        assertFalse(InstanceTransitionValidator.isValid(InstanceState.CRASHED, InstanceState.STARTING));
    }

    @Test
    void rejectsRollbackFromStoppingToRunning() {
        assertFalse(InstanceTransitionValidator.isValid(InstanceState.STOPPING, InstanceState.RUNNING));
    }
}
