package com.ghostdeploy.controller;

import com.ghostdeploy.engine.BaselineService;
import com.ghostdeploy.model.EndpointStats;
import com.ghostdeploy.model.FieldStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContractControllerTest {

    @Mock
    private BaselineService baselineService;
    
    private ContractController contractController;

    @BeforeEach
    void setUp() {
        contractController = new ContractController(baselineService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetContracts() {
        EndpointStats stats = new EndpointStats("test:/api");
        stats.getTotalRequestCount().set(10);
        
        FieldStats fs = new FieldStats("user");
        fs.getPresenceCount().set(8);
        fs.getTypeFrequency().put("STRING", new java.util.concurrent.atomic.AtomicLong(8));
        stats.getFieldStatsMap().put("user", fs);
        
        Map<String, EndpointStats> memoryStats = new ConcurrentHashMap<>();
        memoryStats.put("test:/api", stats);
        
        when(baselineService.getMemoryStats()).thenReturn(memoryStats);
        
        Map<String, Object> result = contractController.getContracts();
        
        assertTrue(result.containsKey("test:/api"));
        Map<String, Object> fields = (Map<String, Object>) result.get("test:/api");
        assertTrue(fields.containsKey("user"));
        
        Map<String, Object> fieldDetails = (Map<String, Object>) fields.get("user");
        assertEquals(0.8, (Double) fieldDetails.get("presenceRate"), 0.001);
        
        Map<String, java.util.concurrent.atomic.AtomicLong> types = 
            (Map<String, java.util.concurrent.atomic.AtomicLong>) fieldDetails.get("types");
        assertEquals(8, types.get("STRING").get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetContractsEmptyStats() {
        EndpointStats stats = new EndpointStats("test:/api");
        stats.getTotalRequestCount().set(0); // Zero requests
        
        FieldStats fs = new FieldStats("user");
        fs.getPresenceCount().set(0);
        stats.getFieldStatsMap().put("user", fs);
        
        Map<String, EndpointStats> memoryStats = new ConcurrentHashMap<>();
        memoryStats.put("test:/api", stats);
        
        when(baselineService.getMemoryStats()).thenReturn(memoryStats);
        
        Map<String, Object> result = contractController.getContracts();
        
        Map<String, Object> fields = (Map<String, Object>) result.get("test:/api");
        assertTrue(fields.isEmpty());
    }
}
