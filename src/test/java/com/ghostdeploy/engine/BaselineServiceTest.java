package com.ghostdeploy.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostdeploy.config.GhostDeployProperties;
import com.ghostdeploy.model.EndpointStats;
import com.ghostdeploy.model.FieldStats;
import com.ghostdeploy.model.StatsSnapshotEntity;
import com.ghostdeploy.repository.StatsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BaselineServiceTest {

    @Mock
    private StatsRepository statsRepository;
    @Mock
    private AnomalyDetector anomalyDetector;
    
    private ObjectMapper objectMapper;
    private GhostDeployProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private BaselineService baselineService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new GhostDeployProperties();
        meterRegistry = new SimpleMeterRegistry();
        baselineService = new BaselineService(statsRepository, anomalyDetector, objectMapper, properties, meterRegistry);
    }

    @Test
    void testInitLoadsFromRepository() throws Exception {
        StatsSnapshotEntity entity = new StatsSnapshotEntity();
        entity.setEndpointKey("test-app:/api");
        entity.setTotalRequestCount(100);
        entity.setAverageResponseTime(50.0);
        entity.setFieldStatsJson("{\"user.name\": 90}");
        
        when(statsRepository.findAll()).thenReturn(Collections.singletonList(entity));
        
        baselineService.init();
        
        Map<String, EndpointStats> statsMap = baselineService.getMemoryStats();
        assertEquals(1, statsMap.size());
        
        EndpointStats stats = statsMap.get("test-app:/api");
        assertNotNull(stats);
        assertEquals(100, stats.getTotalRequestCount().get());
        assertEquals(5000, stats.getCumulativeResponseTime().get()); // 100 * 50.0
        assertTrue(stats.getFieldStatsMap().containsKey("user.name"));
        assertEquals(90, stats.getFieldStatsMap().get("user.name").getPresenceCount().get());
    }

    @Test
    void testInitHandlesBadJson() throws Exception {
        StatsSnapshotEntity entity = new StatsSnapshotEntity();
        entity.setEndpointKey("test-app:/api");
        entity.setFieldStatsJson("invalid json");
        
        when(statsRepository.findAll()).thenReturn(Collections.singletonList(entity));
        
        baselineService.init();
        
        Map<String, EndpointStats> statsMap = baselineService.getMemoryStats();
        EndpointStats stats = statsMap.get("test-app:/api");
        assertTrue(stats.getFieldStatsMap().isEmpty());
    }

    @Test
    void testProcessRequestStatsOnly() {
        baselineService.processRequestStatsOnly("app:/test", 150, 200);
        
        EndpointStats stats = baselineService.getMemoryStats().get("app:/test");
        assertNotNull(stats);
        assertEquals(1, stats.getTotalRequestCount().get());
        assertEquals(150, stats.getCumulativeResponseTime().get());
        assertEquals(1, stats.getStatusCodeFrequency().get(200).get());
        assertTrue(stats.getFieldStatsMap().isEmpty());
    }

    @Test
    void testProcessRequestValidJson() {
        String json = "{\"id\": 1, \"name\": \"Ghost\", \"isActive\": true, \"metadata\": null, \"tags\": [\"a\", \"b\"], \"nested\": {\"key\": 123}}";
        byte[] payload = json.getBytes();
        
        baselineService.processRequest("app:/test", 100, 200, payload, false);
        
        EndpointStats stats = baselineService.getMemoryStats().get("app:/test");
        assertNotNull(stats);
        
        Map<String, FieldStats> fields = stats.getFieldStatsMap();
        assertTrue(fields.containsKey("id"));
        assertTrue(fields.containsKey("name"));
        assertTrue(fields.containsKey("isActive"));
        assertTrue(fields.containsKey("metadata"));
        assertTrue(fields.containsKey("nested.key"));
        
        assertEquals(1, fields.get("id").getPresenceCount().get());
        assertEquals(1, fields.get("id").getTypeFrequency().get("NUMBER").get());
        
        assertEquals(1, fields.get("isActive").getTypeFrequency().get("BOOLEAN").get());
        assertEquals(1, fields.get("metadata").getNullCount().get());
        assertEquals(1, fields.get("nested.key").getTypeFrequency().get("NUMBER").get());
        
        verify(anomalyDetector).detectAnomalies(eq(stats), any(), eq(100L), eq(200));
    }

    @Test
    void testProcessRequestGzip() throws Exception {
        String json = "{\"id\": 1}";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(json.getBytes());
        }
        byte[] payload = baos.toByteArray();
        
        baselineService.processRequest("app:/test-gzip", 100, 200, payload, true);
        
        EndpointStats stats = baselineService.getMemoryStats().get("app:/test-gzip");
        assertNotNull(stats);
        assertTrue(stats.getFieldStatsMap().containsKey("id"));
    }

    @Test
    void testProcessRequestGzipExceedsMaxBytes() throws Exception {
        properties.setMaxBodySizeBytes(5); // very small
        
        String json = "{\"id\": 1234567890}";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(json.getBytes());
        }
        byte[] payload = baos.toByteArray();
        
        baselineService.processRequest("app:/test-gzip", 100, 200, payload, true);
        
        EndpointStats stats = baselineService.getMemoryStats().get("app:/test-gzip");
        assertNotNull(stats);
        assertTrue(stats.getFieldStatsMap().isEmpty()); // Skipped extraction due to size limit
    }

    @Test
    void testProcessRequestInvalidJson() {
        baselineService.processRequest("app:/test", 100, 200, "not json".getBytes(), false);
        
        EndpointStats stats = baselineService.getMemoryStats().get("app:/test");
        assertTrue(stats.getFieldStatsMap().isEmpty());
    }

    @Test
    void testDynamicKeyMasking() {
        String json = "{\"user\": {\"12345\": {\"name\": \"Test\"}, \"550e8400-e29b-41d4-a716-446655440000\": {\"role\": \"admin\"}}}";
        
        baselineService.processRequest("app:/test", 100, 200, json.getBytes(), false);
        
        Map<String, FieldStats> fields = baselineService.getMemoryStats().get("app:/test").getFieldStatsMap();
        assertTrue(fields.containsKey("user.*.name"));
        assertTrue(fields.containsKey("user.*.role"));
        assertFalse(fields.containsKey("user.12345.name"));
    }

    @Test
    void testPersistSnapshots() {
        baselineService.processRequestStatsOnly("app:/test", 100, 200);
        when(statsRepository.findByEndpointKey("app:/test")).thenReturn(Optional.empty());
        
        baselineService.persistSnapshots();
        
        ArgumentCaptor<StatsSnapshotEntity> captor = ArgumentCaptor.forClass(StatsSnapshotEntity.class);
        verify(statsRepository).save(captor.capture());
        
        StatsSnapshotEntity entity = captor.getValue();
        assertEquals("app:/test", entity.getEndpointKey());
        assertEquals(1, entity.getTotalRequestCount());
    }

    @Test
    void testScheduledTtlSweeper() throws Exception {
        properties.setEndpointTtlMinutes(0); // Expire immediately
        
        baselineService.processRequestStatsOnly("app:/test", 100, 200);
        
        // Ensure some time passes so it is evicted
        Thread.sleep(50);
        
        baselineService.scheduledTtlSweeper();
        
        assertTrue(baselineService.getMemoryStats().isEmpty());
    }

    @Test
    void testEvictionLimits() {
        properties.setMaxEndpointsTracked(2);
        
        baselineService.processRequestStatsOnly("app:/test1", 100, 200);
        try { Thread.sleep(2); } catch(Exception e){}
        baselineService.processRequestStatsOnly("app:/test2", 100, 200);
        try { Thread.sleep(2); } catch(Exception e){}
        
        baselineService.processRequestStatsOnly("app:/test3", 100, 200);
        
        assertEquals(2, baselineService.getMemoryStats().size());
        assertFalse(baselineService.getMemoryStats().containsKey("app:/test1"));
    }
    
    @Test
    void testFieldEvictionLimits() {
        properties.setMaxFieldsPerEndpoint(2);
        
        String json1 = "{\"f1\": 1, \"f2\": 2}";
        baselineService.processRequest("app:/test", 100, 200, json1.getBytes(), false);
        
        try { Thread.sleep(10); } catch(Exception e){}
        
        String json2 = "{\"f3\": 3}";
        baselineService.processRequest("app:/test", 100, 200, json2.getBytes(), false);
        
        EndpointStats stats = baselineService.getMemoryStats().get("app:/test");
        assertEquals(2, stats.getFieldStatsMap().size());
        // f1 or f2 will be evicted
    }
}
