package com.ghostdeploy.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostdeploy.config.GhostDeployProperties;
import com.ghostdeploy.model.EndpointStats;
import com.ghostdeploy.repository.StatsRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MurmurHash3-based schema fingerprinting inside BaselineService.
 * Verifies determinism, sensitivity to structure changes, and async-only
 * execution.
 */
class SchemaFingerprintTest {

    private BaselineService baselineService;
    private GhostDeployProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GhostDeployProperties();
        properties.setWarmupRequests(0); // skip warmup
        baselineService = new BaselineService(
                mock(StatsRepository.class),
                mock(AnomalyDetector.class),
                new ObjectMapper(),
                properties,
                new SimpleMeterRegistry());
    }

    @Test
    void testSameStructureProducesSameFingerprint() {
        String json1 = "{\"id\": 1, \"name\": \"Alice\", \"active\": true}";
        String json2 = "{\"id\": 2, \"name\": \"Bob\", \"active\": false}";

        baselineService.processRequest("app:/test", 100, 200, json1.getBytes(), false);
        String fp1 = baselineService.getMemoryStats().get("app:/test").getSchemaFingerprint();

        // Reset
        baselineService.getMemoryStats().clear();

        baselineService.processRequest("app:/test", 100, 200, json2.getBytes(), false);
        String fp2 = baselineService.getMemoryStats().get("app:/test").getSchemaFingerprint();

        assertEquals(fp1, fp2, "Same field structure (same names + types) must produce the same fingerprint");
    }

    @Test
    void testDifferentFieldNameProducesDifferentFingerprint() {
        String json1 = "{\"id\": 1, \"name\": \"Alice\"}";
        String json2 = "{\"id\": 1, \"email\": \"a@b.com\"}"; // 'email' instead of 'name'

        baselineService.processRequest("app:/test", 100, 200, json1.getBytes(), false);
        String fp1 = baselineService.getMemoryStats().get("app:/test").getSchemaFingerprint();

        baselineService.getMemoryStats().clear();

        baselineService.processRequest("app:/test", 100, 200, json2.getBytes(), false);
        String fp2 = baselineService.getMemoryStats().get("app:/test").getSchemaFingerprint();

        assertNotEquals(fp1, fp2, "Different field names must produce different fingerprints");
    }

    @Test
    void testDifferentFieldTypeProducesDifferentFingerprint() {
        String json1 = "{\"id\": 1}"; // id is NUMBER
        String json2 = "{\"id\": \"abc\"}"; // id is STRING

        baselineService.processRequest("app:/test", 100, 200, json1.getBytes(), false);
        String fp1 = baselineService.getMemoryStats().get("app:/test").getSchemaFingerprint();

        baselineService.getMemoryStats().clear();

        baselineService.processRequest("app:/test", 100, 200, json2.getBytes(), false);
        String fp2 = baselineService.getMemoryStats().get("app:/test").getSchemaFingerprint();

        assertNotEquals(fp1, fp2, "Same field name with different types must produce different fingerprints");
    }

    @Test
    void testFingerprintIsDeterministicAcrossMultipleRequests() {
        String json = "{\"a\": 1, \"b\": \"x\", \"c\": true}";

        baselineService.processRequest("app:/fp-test", 100, 200, json.getBytes(), false);
        String fp1 = baselineService.getMemoryStats().get("app:/fp-test").getSchemaFingerprint();

        // Process same structure again — fingerprint must not change
        baselineService.processRequest("app:/fp-test", 100, 200, json.getBytes(), false);
        String fp2 = baselineService.getMemoryStats().get("app:/fp-test").getSchemaFingerprint();

        assertEquals(fp1, fp2, "Fingerprint must be stable across repeated same-structure requests");
    }

    @Test
    void testFingerprintStoredAsHexString() {
        String json = "{\"id\": 1}";
        baselineService.processRequest("app:/hex-test", 100, 200, json.getBytes(), false);

        String fp = baselineService.getMemoryStats().get("app:/hex-test").getSchemaFingerprint();
        assertNotNull(fp);
        assertFalse(fp.isEmpty());
        // MurmurHash3 result in hex — must be a valid hex string
        assertTrue(fp.matches("[0-9a-f]+"), "Fingerprint must be a hex string, got: " + fp);
    }

    @Test
    void testFingerprintNotUpdatedWhenNoFields() {
        // Stats-only call (no JSON body) — fingerprint must remain empty
        baselineService.processRequestStatsOnly("app:/no-body", 100, 200);

        EndpointStats stats = baselineService.getMemoryStats().get("app:/no-body");
        assertEquals("", stats.getSchemaFingerprint(), "Fingerprint must remain empty when no fields extracted");
    }
}
