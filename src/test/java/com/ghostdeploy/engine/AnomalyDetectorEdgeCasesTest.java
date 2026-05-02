package com.ghostdeploy.engine;

import com.ghostdeploy.alerting.AlertHandler;
import com.ghostdeploy.config.GhostDeployProperties;
import com.ghostdeploy.model.EndpointStats;
import com.ghostdeploy.model.FieldDetail;
import com.ghostdeploy.model.FieldStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.*;

/**
 * Covers branches not exercised by AnomalyDetectorTest:
 * - Field below minSamples / minPresenceRate → no alert
 * - Status code < 400 → no alert
 * - Status code ≥ 400 but already common (rate ≥ 5%) → no alert
 * - Type is NULL → skip type drift check
 * - Null field but historically high null rate → no alert
 * - Field drop but historical presence below threshold → no alert
 * - Extra field detected before 2× warmup → no alert
 * - Response time NOT spiking → no alert
 */
class AnomalyDetectorEdgeCasesTest {

    private GhostDeployProperties properties;
    private AlertHandler alertHandler;
    private AnomalyDetector detector;

    @BeforeEach
    void setUp() {
        properties = new GhostDeployProperties();
        properties.setWarmupRequests(10);
        properties.getThreshold().setMinSamples(30);        // default
        properties.getThreshold().setMinPresenceRate(0.30); // default
        alertHandler = mock(AlertHandler.class);
        detector = new AnomalyDetector(properties, alertHandler, new SimpleMeterRegistry());
    }

    @Test
    void testFieldBelowMinSamplesSkipped() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);

        FieldStats fs = new FieldStats("rare.field");
        fs.getPresenceCount().set(5); // below minSamples=30
        stats.getFieldStatsMap().put("rare.field", fs);

        detector.detectAnomalies(stats, Collections.emptyList(), 100, 200);

        verifyNoInteractions(alertHandler);
    }

    @Test
    void testFieldBelowMinPresenceRateSkipped() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);

        FieldStats fs = new FieldStats("sparse.field");
        fs.getPresenceCount().set(29); // presenceRate = 0.29 < 0.30
        stats.getFieldStatsMap().put("sparse.field", fs);

        detector.detectAnomalies(stats, Collections.emptyList(), 100, 200);

        verifyNoInteractions(alertHandler);
    }

    @Test
    void testFieldDropBelowPresenceThresholdNoAlert() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);

        FieldStats fs = new FieldStats("optional.field");
        fs.getPresenceCount().set(50); // 50% presence, below 90% expectedPresence threshold
        stats.getFieldStatsMap().put("optional.field", fs);

        // Field is missing from current request
        detector.detectAnomalies(stats, Collections.emptyList(), 100, 200);

        verifyNoInteractions(alertHandler);
    }

    @Test
    void testStatusCodeSuccessfulNoAlert() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);

        // 200 is common (99%)
        stats.getStatusCodeFrequency().put(200, new AtomicLong(99));

        detector.detectAnomalies(stats, Collections.emptyList(), 100, 200);

        verifyNoInteractions(alertHandler);
    }

    @Test
    void testStatusCodeDriftAlreadyCommonNoAlert() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);

        // 404 has been seen 10% of the time (above 5% threshold)
        stats.getStatusCodeFrequency().put(404, new AtomicLong(10));

        detector.detectAnomalies(stats, Collections.emptyList(), 100, 404);

        verifyNoInteractions(alertHandler);
    }

    @Test
    void testTypeDriftTypeIsNullSkipped() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);

        FieldStats fs = new FieldStats("nullable.field");
        fs.getPresenceCount().set(100);
        // High historic null rate (80%) — null spike threshold won't fire either
        fs.getNullCount().set(80);
        stats.getFieldStatsMap().put("nullable.field", fs);

        // Type is NULL — should skip type drift check
        // (null spike also won't fire because historical null rate is already above threshold)
        FieldDetail currentDetail = new FieldDetail("nullable.field", "NULL", null, true);
        detector.detectAnomalies(stats, Arrays.asList(currentDetail), 100, 200);

        // Verify no TYPE_DRIFT alert was triggered (only thing we're verifying here)
        verify(alertHandler, never()).handle(argThat(a -> "TYPE_DRIFT".equals(a.getIssueType())));
    }

    @Test
    void testNullRateAlreadyHighNoAlert() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);

        FieldStats fs = new FieldStats("nullable.field");
        fs.getPresenceCount().set(100);
        fs.getNullCount().set(60); // 60% null rate historically — above spike threshold
        stats.getFieldStatsMap().put("nullable.field", fs);

        FieldDetail currentDetail = new FieldDetail("nullable.field", "NULL", null, true);
        detector.detectAnomalies(stats, Arrays.asList(currentDetail), 100, 200);

        verifyNoInteractions(alertHandler);
    }

    @Test
    void testResponseTimeWithinNormalNoAlert() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);
        stats.getCumulativeResponseTime().set(10000); // avg = 100ms

        // 250ms is only 2.5x average — under the 3x threshold
        detector.detectAnomalies(stats, Collections.emptyList(), 250, 200);

        verifyNoInteractions(alertHandler);
    }

    @Test
    void testResponseTimeZeroAverageNoAlert() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(0); // no data → avg = 0

        detector.detectAnomalies(stats, Collections.emptyList(), 500, 200);

        verifyNoInteractions(alertHandler);
    }

    @Test
    void testExtraFieldBeforeDoubleWarmupNoAlert() {
        properties.setWarmupRequests(10); // double warmup threshold = 20
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(15); // less than 2 * warmupRequests=10

        FieldDetail currentDetail = new FieldDetail("brand.new.field", "STRING", null, false);
        detector.detectAnomalies(stats, Arrays.asList(currentDetail), 100, 200);

        verifyNoInteractions(alertHandler);
    }

    @Test
    void testTypeDriftBelowThresholdNoAlert() {
        EndpointStats stats = new EndpointStats("app:/test");
        stats.getTotalRequestCount().set(100);

        FieldStats fs = new FieldStats("mixed.field");
        fs.getPresenceCount().set(100);
        fs.getTypeFrequency().put("NUMBER", new AtomicLong(90));
        fs.getTypeFrequency().put("STRING", new AtomicLong(9)); // 9% — below 20% sensitivity
        stats.getFieldStatsMap().put("mixed.field", fs);

        // STRING bump: 10/101 ≈ 9.9% — still below 20%
        FieldDetail currentDetail = new FieldDetail("mixed.field", "STRING", null, false);
        detector.detectAnomalies(stats, Arrays.asList(currentDetail), 100, 200);

        verifyNoInteractions(alertHandler);
    }
}
