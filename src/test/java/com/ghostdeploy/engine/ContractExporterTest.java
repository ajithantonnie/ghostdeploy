package com.ghostdeploy.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostdeploy.model.EndpointStats;
import com.ghostdeploy.model.FieldStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContractExporterTest {

    @Mock
    private BaselineService baselineService;

    private ContractExporter contractExporter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        contractExporter = new ContractExporter(baselineService, objectMapper);
    }

    @Test
    void testExportEmptyStats() throws Exception {
        when(baselineService.getMemoryStats()).thenReturn(new ConcurrentHashMap<>());

        String json = contractExporter.exportSchemaSnapshot();

        assertNotNull(json);
        // Pretty-printer may produce "{ }" or "{}"
        assertTrue(json.replace(" ", "").replace("\n", "").replace("\r", "").equals("{}"),
                "Expected empty JSON object, got: " + json);
    }

    @Test
    void testExportWithEndpointAndFields() throws Exception {
        EndpointStats stats = new EndpointStats("test-app:/api/users");
        stats.getTotalRequestCount().set(100);
        stats.getCumulativeResponseTime().set(5000);

        FieldStats idField = new FieldStats("id");
        idField.getPresenceCount().set(100);
        idField.getNullCount().set(0);
        idField.getTypeFrequency().put("NUMBER", new AtomicLong(100));
        stats.getFieldStatsMap().put("id", idField);

        FieldStats nameField = new FieldStats("name");
        nameField.getPresenceCount().set(90);
        nameField.getNullCount().set(10);
        nameField.getTypeFrequency().put("STRING", new AtomicLong(80));
        nameField.getTypeFrequency().put("NULL", new AtomicLong(10));
        stats.getFieldStatsMap().put("name", nameField);

        ConcurrentHashMap<String, EndpointStats> memoryStats = new ConcurrentHashMap<>();
        memoryStats.put("test-app:/api/users", stats);

        when(baselineService.getMemoryStats()).thenReturn(memoryStats);

        String json = contractExporter.exportSchemaSnapshot();

        assertNotNull(json);
        assertTrue(json.contains("test-app:/api/users"));
        assertTrue(json.contains("totalRequests"));
        assertTrue(json.contains("averageResponseTimeMs"));
        assertTrue(json.contains("presenceProbability"));
        assertTrue(json.contains("nullProbability"));
        assertTrue(json.contains("typeDistribution"));
        assertTrue(json.contains("id"));
        assertTrue(json.contains("name"));
    }

    @Test
    void testExportNullRateCalculation() throws Exception {
        EndpointStats stats = new EndpointStats("test-app:/api/orders");
        stats.getTotalRequestCount().set(50);

        FieldStats fieldStats = new FieldStats("status");
        fieldStats.getPresenceCount().set(50);
        fieldStats.getNullCount().set(10); // 20% null rate

        stats.getFieldStatsMap().put("status", fieldStats);

        ConcurrentHashMap<String, EndpointStats> memoryStats = new ConcurrentHashMap<>();
        memoryStats.put("test-app:/api/orders", stats);
        when(baselineService.getMemoryStats()).thenReturn(memoryStats);

        String json = contractExporter.exportSchemaSnapshot();

        // Parse back to verify null rate
        var root = objectMapper.readTree(json);
        double nullProb = root.get("test-app:/api/orders")
                .get("fields").get("status").get("nullProbability").asDouble();
        assertEquals(0.2, nullProb, 0.001);
    }

    @Test
    void testExportPresenceRateZeroWhenNoPresence() throws Exception {
        EndpointStats stats = new EndpointStats("test-app:/api/orders");
        stats.getTotalRequestCount().set(50);

        FieldStats fieldStats = new FieldStats("optional");
        fieldStats.getPresenceCount().set(0); // Zero presence count
        stats.getFieldStatsMap().put("optional", fieldStats);

        ConcurrentHashMap<String, EndpointStats> memoryStats = new ConcurrentHashMap<>();
        memoryStats.put("test-app:/api/orders", stats);
        when(baselineService.getMemoryStats()).thenReturn(memoryStats);

        String json = contractExporter.exportSchemaSnapshot();

        var root = objectMapper.readTree(json);
        double nullProb = root.get("test-app:/api/orders")
                .get("fields").get("optional").get("nullProbability").asDouble();
        assertEquals(0.0, nullProb, 0.001);
    }
}
