package com.example.baseplate.agent.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentRuntimeFactoryMaxItersTest {

    @Test
    void negativeMeansUnlimited() {
        assertEquals(Integer.MAX_VALUE, AgentRuntimeFactory.resolveMaxIters(-1, 4));
        assertEquals(Integer.MAX_VALUE, AgentRuntimeFactory.resolveMaxIters(-100, 4));
    }

    @Test
    void normalValueIsKept() {
        assertEquals(10, AgentRuntimeFactory.resolveMaxIters(10, 4));
        assertEquals(1, AgentRuntimeFactory.resolveMaxIters(1, 4));
    }

    @Test
    void zeroClampsToLowerBound() {
        assertEquals(1, AgentRuntimeFactory.resolveMaxIters(0, 4));
    }

    @Test
    void oversizedClampsToUpperBound() {
        assertEquals(1000, AgentRuntimeFactory.resolveMaxIters(5000, 4));
    }

    @Test
    void missingOrInvalidFallsBackToDefault() {
        assertEquals(4, AgentRuntimeFactory.resolveMaxIters(null, 4));
        assertEquals(4, AgentRuntimeFactory.resolveMaxIters("abc", 4));
        assertEquals(Integer.MAX_VALUE, AgentRuntimeFactory.resolveMaxIters(null, -1));
    }
}
