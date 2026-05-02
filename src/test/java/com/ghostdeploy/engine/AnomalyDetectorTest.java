package com.ghostdeploy.engine;

import com.ghostdeploy.alerting.AlertHandler;
import com.ghostdeploy.config.GhostDeployProperties;
import com.ghostdeploy.model.Alert;
import com.ghostdeploy.model.EndpointStats;
import com.ghostdeploy.model.FieldDetail;
import com.ghostdeploy.model.FieldStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AnomalyDetectorTest {

    private GhostDeployProperties properties;
    private AlertHandler alertHandler;
    private AnomalyDetector detector;

    @BeforeEach
    void setUp() {
        properties = new GhostDeployProperties();
        properties.setWarmupRequests(10); // Low warm up for tests
        properties.getThreshold().setMinSamples(0); // For test environment
        properties.getThreshold().setMinPresenceRate(0.0);
        alertHandler = mock(AlertHandler.class);
        detector = new AnomalyDetector(properties, alertHandler, new SimpleMeterRegistry());
    }

    @Test
    void testWarmupPhaseIgnoresAnomalies() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(5); // Below warmup of 10
        
        detector.detectAnomalies(stats, Collections.emptyList(), 1000, 200);
        verifyNoInteractions(alertHandler);
    }

    @Test
    void testDetectFieldDrop() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);
        
        FieldStats fs = new FieldStats("user.name");
        fs.getPresenceCount().set(95); // 95% historical presence
        stats.getFieldStatsMap().put("user.name", fs);

        // Current request is missing "user.name"
        detector.detectAnomalies(stats, Collections.emptyList(), 100, 200);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertHandler).handle(captor.capture());
        
        Alert alert = captor.getValue();
        assertEquals("FIELD_DROP", alert.getIssueType());
        assertEquals("HIGH", alert.getSeverity());
    }

    @Test
    void testDetectTypeDrift() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(99);
        
        FieldStats fs = new FieldStats("user.age");
        fs.getPresenceCount().set(99); // Historical total is 99
        fs.getTypeFrequency().put("NUMBER", new java.util.concurrent.atomic.AtomicLong(80)); // 80 numbers
        fs.getTypeFrequency().put("STRING", new java.util.concurrent.atomic.AtomicLong(19)); // 19 strings
        stats.getFieldStatsMap().put("user.age", fs);

        // Current request has it as a STRING, which will bump its count to 20, exactly 20% of 100
        FieldDetail currentDetail = new FieldDetail("user.age", "STRING", null, false);
        
        detector.detectAnomalies(stats, Arrays.asList(currentDetail), 100, 200);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertHandler).handle(captor.capture());
        
        Alert alert = captor.getValue();
        assertEquals("TYPE_DRIFT", alert.getIssueType());
    }

    @Test
    void testResponseTimeSpike() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);
        stats.getCumulativeResponseTime().set(10000); // avg 100ms
        
        detector.detectAnomalies(stats, Collections.emptyList(), 400, 200); // 400ms > 3 * 100ms
        
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertHandler).handle(captor.capture());
        assertEquals("RESPONSE_TIME_SPIKE", captor.getValue().getIssueType());
    }

    @Test
    void testStatusCodeDrift() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);
        stats.getStatusCodeFrequency().put(200, new java.util.concurrent.atomic.AtomicLong(99));
        stats.getStatusCodeFrequency().put(500, new java.util.concurrent.atomic.AtomicLong(1)); // 1% error
        
        detector.detectAnomalies(stats, Collections.emptyList(), 100, 500); // unexpected 500
        
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertHandler).handle(captor.capture());
        assertEquals("STATUS_CODE_DRIFT", captor.getValue().getIssueType());
    }

    @Test
    void testNullRateSpike() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);
        
        FieldStats fs = new FieldStats("user.name");
        fs.getPresenceCount().set(100);
        fs.getNullCount().set(0); // Never null historically
        stats.getFieldStatsMap().put("user.name", fs);

        FieldDetail currentDetail = new FieldDetail("user.name", "NULL", null, true);
        
        detector.detectAnomalies(stats, Arrays.asList(currentDetail), 100, 200);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertHandler).handle(captor.capture());
        assertEquals("NULL_RATE_SPIKE", captor.getValue().getIssueType());
    }

    @Test
    void testExtraFieldDetection() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);
        // Field "new.field" is NOT in stats
        
        FieldDetail currentDetail = new FieldDetail("new.field", "STRING", null, false);
        
        detector.detectAnomalies(stats, Arrays.asList(currentDetail), 100, 200);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertHandler).handle(captor.capture());
        assertEquals("EXTRA_FIELD", captor.getValue().getIssueType());
    }

    @Test
    void testDebouncing() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);
        stats.getCumulativeResponseTime().set(10000); // avg 100ms
        
        detector.detectAnomalies(stats, Collections.emptyList(), 400, 200); // triggers alert
        detector.detectAnomalies(stats, Collections.emptyList(), 400, 200); // should be debounced
        
        verify(alertHandler, times(1)).handle(any());
    }
}
