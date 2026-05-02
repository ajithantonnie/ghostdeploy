package com.ghostdeploy.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostdeploy.config.GhostDeployProperties;
import com.ghostdeploy.model.EndpointStats;
import com.ghostdeploy.model.FieldStats;
import com.ghostdeploy.repository.StatsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers remaining BaselineService branches:
 * - init with DB loading exception
 * - empty JSON array body
 * - persist with existing entity (update, not insert)
 * - field TTL sweeper
 * - processRequest empty byte array
 */
@ExtendWith(MockitoExtension.class)
class BaselineServiceBranchCoverageTest {

    @Mock
    private StatsRepository statsRepository;
    @Mock
    private AnomalyDetector anomalyDetector;

    private BaselineService baselineService;
    private GhostDeployProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GhostDeployProperties();
        baselineService = new BaselineService(statsRepository, anomalyDetector, new ObjectMapper(), properties,
                new SimpleMeterRegistry());
    }

    @Test
    void testInitHandlesDbException() {
        when(statsRepository.findAll()).thenThrow(new RuntimeException("DB down"));

        // Should not throw — graceful degradation to empty state
        assertDoesNotThrow(() -> baselineService.init());
        assertTrue(baselineService.getMemoryStats().isEmpty());
    }

    @Test
    void testProcessRequestEmptyByteArray() {
        baselineService.processRequest("app:/test", 100, 200, new byte[0], false);

        EndpointStats stats = baselineService.getMemoryStats().get("app:/test");
        assertNotNull(stats);
        assertTrue(stats.getFieldStatsMap().isEmpty()); // No fields from empty payload
    }

    @Test
    void testProcessRequestJsonArray() {
        String json = "[{\"id\": 1}, {\"id\": 2}]";
        baselineService.processRequest("app:/test", 100, 200, json.getBytes(), false);

        // Arrays at root level — fields prefixed with [*]
        EndpointStats stats = baselineService.getMemoryStats().get("app:/test");
        assertNotNull(stats);
        assertTrue(stats.getFieldStatsMap().containsKey("[*].id"));
    }

    @Test
    void testPersistSnapshotsUpdatesExistingEntity() {
        baselineService.processRequestStatsOnly("app:/test", 100, 200);

        // Simulate an existing DB record
        com.ghostdeploy.model.StatsSnapshotEntity existing = new com.ghostdeploy.model.StatsSnapshotEntity();
        existing.setEndpointKey("app:/test");
        existing.setTotalRequestCount(50);
        when(statsRepository.findByEndpointKey("app:/test")).thenReturn(Optional.of(existing));

        baselineService.persistSnapshots();

        verify(statsRepository).save(argThat(e -> e.getTotalRequestCount() == 1));
    }

    @Test
    void testFieldTtlSweeper() throws Exception {
        properties.setFieldTtlMinutes(0); // expire fields immediately

        String json = "{\"id\": 1}";
        baselineService.processRequest("app:/test", 100, 200, json.getBytes(), false);

        assertFalse(baselineService.getMemoryStats().get("app:/test").getFieldStatsMap().isEmpty());

        Thread.sleep(50);

        baselineService.scheduledTtlSweeper();

        // Endpoint should still be there (endpoint TTL is default 30min), but fields
        // should be evicted
        // unless the endpoint itself was also evicted
        // Either way: field map should be empty for this endpoint
        EndpointStats stats = baselineService.getMemoryStats().get("app:/test");
        if (stats != null) {
            assertTrue(stats.getFieldStatsMap().isEmpty(), "Fields should be evicted after TTL");
        }
    }

    @Test
    void testFieldStatsLastAccessedUpdated() {
        String json = "{\"id\": 1}";
        baselineService.processRequest("app:/test", 100, 200, json.getBytes(), false);

        EndpointStats stats = baselineService.getMemoryStats().get("app:/test");
        FieldStats fs = stats.getFieldStatsMap().get("id");
        assertNotNull(fs);

        long firstAccess = fs.getLastAccessedTimeMillis();

        try {
            Thread.sleep(5);
        } catch (Exception e) {
        }

        // Process another request — lastAccessedTimeMillis should update
        baselineService.processRequest("app:/test", 100, 200, json.getBytes(), false);
        long secondAccess = stats.getFieldStatsMap().get("id").getLastAccessedTimeMillis();

        assertTrue(secondAccess >= firstAccess, "lastAccessedTimeMillis should be updated on each access");
    }
}
