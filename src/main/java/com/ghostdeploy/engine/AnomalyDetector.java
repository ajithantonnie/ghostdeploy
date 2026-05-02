package com.ghostdeploy.engine;

import com.ghostdeploy.alerting.AlertHandler;
import com.ghostdeploy.config.GhostDeployProperties;
import com.ghostdeploy.model.Alert;
import com.ghostdeploy.model.EndpointStats;
import com.ghostdeploy.model.FieldDetail;
import com.ghostdeploy.model.FieldStats;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnomalyDetector {

    private final GhostDeployProperties properties;
    private final AlertHandler alertHandler;
    private final MeterRegistry meterRegistry;
    
    // Tracks last alert time per unique anomaly key for debouncing
    private final Map<String, Long> lastAlertTimeMap = new ConcurrentHashMap<>();

    public void detectAnomalies(EndpointStats stats, List<FieldDetail> currentFields, long currentResponseTime, int statusCode) {
        long totalRequests = stats.getTotalRequestCount().get();

        // Warm-up phase check
        if (totalRequests < properties.getWarmupRequests()) {
            return;
        }

        detectResponseTimeSpike(stats, currentResponseTime);
        detectStatusCodeDrift(stats, statusCode, totalRequests);
        detectFieldAnomalies(stats, currentFields, totalRequests);
    }

    private void detectResponseTimeSpike(EndpointStats stats, long currentResponseTime) {
        double avgResponseTime = stats.getAverageResponseTime();
        if (avgResponseTime > 0 && currentResponseTime > (avgResponseTime * 3)) {
            String description = String.format("Response time spiked to %d ms. Historical average is %.2f ms.",
                    currentResponseTime, avgResponseTime);
            
            triggerAlertDebounced(stats.getEndpointKey(), "RESPONSE_TIME_SPIKE", description, "MEDIUM");
        }
    }

    private void detectStatusCodeDrift(EndpointStats stats, int statusCode, long totalRequests) {
        long currentStatusCount = stats.getStatusCodeFrequency().getOrDefault(statusCode, new java.util.concurrent.atomic.AtomicLong(0)).get();
        double historicalRate = (double) currentStatusCount / totalRequests;

        if (historicalRate < 0.05 && statusCode >= 400) {
            String description = String.format("Unexpected status code %d detected. Historical occurrence rate: %.1f%%",
                    statusCode, historicalRate * 100);
            
            triggerAlertDebounced(stats.getEndpointKey(), "STATUS_CODE_DRIFT:" + statusCode, description, "HIGH");
        }
    }

    private void detectFieldAnomalies(EndpointStats stats, List<FieldDetail> currentFields, long totalRequests) {
        GhostDeployProperties.Threshold thresholdCfg = properties.getThreshold();
        
        double expectedPresenceThreshold = thresholdCfg.getFieldExpectedPresence();
        double nullRateSpikeThreshold = thresholdCfg.getNullRateSpike();
        double typeDriftSensitivity = thresholdCfg.getTypeDriftSensitivity();
        int minSamples = thresholdCfg.getMinSamples();
        double minPresenceRate = thresholdCfg.getMinPresenceRate();

        Set<String> currentFieldNames = currentFields.stream().map(FieldDetail::getFieldName).collect(Collectors.toSet());

        // Process tracked fields
        for (Map.Entry<String, FieldStats> entry : stats.getFieldStatsMap().entrySet()) {
            String fieldName = entry.getKey();
            FieldStats fieldStats = entry.getValue();

            long fieldPresenceCount = fieldStats.getPresenceCount().get();
            double historicalPresenceRate = (double) fieldPresenceCount / totalRequests;

            // Baseline eligibility check
            if (fieldPresenceCount < minSamples || historicalPresenceRate < minPresenceRate) {
                continue;
            }

            // 1. Detect missing fields (Field Drop)
            if (!currentFieldNames.contains(fieldName)) {
                if (historicalPresenceRate >= expectedPresenceThreshold) {
                    String description = String.format("Field '%s' is missing. Historical presence: %.1f%%",
                            fieldName, historicalPresenceRate * 100);
                    triggerAlertDebounced(stats.getEndpointKey(), "FIELD_DROP:" + fieldName, description, "HIGH");
                }
            } else {
                FieldDetail currentDetail = currentFields.stream()
                        .filter(f -> f.getFieldName().equals(fieldName))
                        .findFirst().orElse(null);

                if (currentDetail != null) {
                    // 2. Detect Null Rate Spike
                    double historicalNullRate = (double) fieldStats.getNullCount().get() / fieldPresenceCount;
                    if (currentDetail.isNull() && historicalNullRate < nullRateSpikeThreshold) {
                         String description = String.format("Field '%s' is unexpectedly null. Historical null rate: %.1f%%",
                                 fieldName, historicalNullRate * 100);
                         triggerAlertDebounced(stats.getEndpointKey(), "NULL_RATE_SPIKE:" + fieldName, description, "MEDIUM");
                    }

                    // 3. Detect Type Drift
                    if (currentDetail.getType() != null && !currentDetail.getType().equals("NULL")) {
                        long typeCount = fieldStats.getTypeFrequency()
                            .getOrDefault(currentDetail.getType(), new java.util.concurrent.atomic.AtomicLong(0)).get();
                        
                        // We simulate what the rate will be after this request
                        double newTypeRate = (double) (typeCount + 1) / (fieldPresenceCount + 1);
                        
                        // If this type hasn't been the dominant one, and its share just crossed the sensitivity threshold (e.g. 20%)
                        if (newTypeRate >= typeDriftSensitivity && typeCount < (fieldPresenceCount * typeDriftSensitivity)) {
                            String description = String.format("Field '%s' type changed to %s. New type share: %.1f%%",
                                    fieldName, currentDetail.getType(), newTypeRate * 100);
                            triggerAlertDebounced(stats.getEndpointKey(), "TYPE_DRIFT:" + fieldName, description, "HIGH");
                        }
                    }
                }
            }
        }

        // 4. Detect Extra Fields
        for (FieldDetail detail : currentFields) {
            if (!stats.getFieldStatsMap().containsKey(detail.getFieldName())) {
                if (totalRequests >= properties.getWarmupRequests() * 2) {
                    String description = String.format("Unexpected new field '%s' detected.", detail.getFieldName());
                    triggerAlertDebounced(stats.getEndpointKey(), "EXTRA_FIELD:" + detail.getFieldName(), description, "LOW");
                }
            }
        }
    }

    private void triggerAlertDebounced(String endpointKey, String issueKey, String description, String severity) {
        String uniqueAlertKey = endpointKey + "|" + issueKey;
        long currentTime = System.currentTimeMillis();
        long debounceMillis = properties.getAlertDebounceMinutes() * 60 * 1000L;

        lastAlertTimeMap.compute(uniqueAlertKey, (key, lastTime) -> {
            if (lastTime == null || (currentTime - lastTime) > debounceMillis) {
                // Trigger real alert
                String issueType = issueKey.contains(":") ? issueKey.split(":")[0] : issueKey;
                
                Alert alert = Alert.builder()
                        .endpointKey(endpointKey)
                        .issueType(issueType)
                        .description(description)
                        .severity(severity)
                        .build();
                
                alertHandler.handle(alert);
                meterRegistry.counter("ghostdeploy.anomalies.detected", "endpoint", endpointKey, "type", issueType).increment();
                
                return currentTime;
            }
            return lastTime; // Debounced
        });
    }
}
