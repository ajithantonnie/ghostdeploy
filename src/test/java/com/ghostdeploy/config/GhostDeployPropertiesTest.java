package com.ghostdeploy.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GhostDeployPropertiesTest {

    @Test
    void testDefaultValues() {
        GhostDeployProperties props = new GhostDeployProperties();

        assertTrue(props.isEnabled());
        assertEquals(0.5, props.getSamplingRate(), 0.001);  // actual default is 0.5
        assertEquals(10240, props.getMaxBodySizeBytes());
        assertEquals(200, props.getWarmupRequests());
        assertEquals(500, props.getMaxEndpointsTracked());
        assertEquals(50, props.getMaxFieldsPerEndpoint());
        assertEquals(30, props.getEndpointTtlMinutes());
        assertEquals(60, props.getFieldTtlMinutes());
        assertEquals(10, props.getAlertDebounceMinutes());
        assertFalse(props.getContracts().isEnabled());
        assertEquals(1000, props.getAsync().getQueueCapacity());
        // Default excludes: /health, /actuator, /error, /ghostdeploy
        assertEquals(4, props.getExcludePaths().size());
    }

    @Test
    void testThresholdDefaults() {
        GhostDeployProperties.Threshold t = new GhostDeployProperties().getThreshold();

        assertEquals(0.90, t.getFieldExpectedPresence(), 0.001);
        assertEquals(0.20, t.getFieldDrop(), 0.001);
        assertEquals(0.20, t.getTypeDriftSensitivity(), 0.001);
        assertEquals(0.30, t.getNullRateSpike(), 0.001);   // actual default
        assertEquals(30, t.getMinSamples());
        assertEquals(0.30, t.getMinPresenceRate(), 0.001);
    }

    @Test
    void testSettersAndGetters() {
        GhostDeployProperties props = new GhostDeployProperties();

        props.setEnabled(false);
        assertFalse(props.isEnabled());

        props.setSamplingRate(0.5);
        assertEquals(0.5, props.getSamplingRate(), 0.001);

        props.setMaxBodySizeBytes(5120);
        assertEquals(5120, props.getMaxBodySizeBytes());

        props.setWarmupRequests(100);
        assertEquals(100, props.getWarmupRequests());

        props.setMaxEndpointsTracked(200);
        assertEquals(200, props.getMaxEndpointsTracked());

        props.setMaxFieldsPerEndpoint(25);
        assertEquals(25, props.getMaxFieldsPerEndpoint());

        props.setEndpointTtlMinutes(15);
        assertEquals(15, props.getEndpointTtlMinutes());

        props.setFieldTtlMinutes(30);
        assertEquals(30, props.getFieldTtlMinutes());

        props.setAlertDebounceMinutes(5);
        assertEquals(5, props.getAlertDebounceMinutes());

        props.setExcludePaths(List.of("/health", "/actuator"));
        assertEquals(2, props.getExcludePaths().size());
        assertEquals("/health", props.getExcludePaths().get(0));
    }

    @Test
    void testContractsSetters() {
        GhostDeployProperties.Contracts c = new GhostDeployProperties.Contracts();
        assertFalse(c.isEnabled());
        c.setEnabled(true);
        assertTrue(c.isEnabled());
    }

    @Test
    void testAsyncSetters() {
        GhostDeployProperties.Async a = new GhostDeployProperties.Async();
        assertEquals(1000, a.getQueueCapacity());
        a.setQueueCapacity(500);
        assertEquals(500, a.getQueueCapacity());
    }

    @Test
    void testThresholdSetters() {
        GhostDeployProperties.Threshold t = new GhostDeployProperties.Threshold();

        t.setFieldExpectedPresence(0.85);
        assertEquals(0.85, t.getFieldExpectedPresence(), 0.001);

        t.setFieldDrop(0.15);
        assertEquals(0.15, t.getFieldDrop(), 0.001);

        t.setTypeDriftSensitivity(0.30);
        assertEquals(0.30, t.getTypeDriftSensitivity(), 0.001);

        t.setNullRateSpike(0.10);
        assertEquals(0.10, t.getNullRateSpike(), 0.001);

        t.setMinSamples(50);
        assertEquals(50, t.getMinSamples());

        t.setMinPresenceRate(0.50);
        assertEquals(0.50, t.getMinPresenceRate(), 0.001);
    }
}
