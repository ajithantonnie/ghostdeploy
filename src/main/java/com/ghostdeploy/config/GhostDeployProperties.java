package com.ghostdeploy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "ghostdeploy")
public class GhostDeployProperties {

    private boolean enabled = true;
    private double samplingRate = 0.5; // Default updated to 0.5
    private int maxBodySizeBytes = 10240; // 10KB
    private int maxEndpointsTracked = 500;
    private int maxFieldsPerEndpoint = 50; // Default updated to 50
    private int warmupRequests = 200;
    
    private int endpointTtlMinutes = 30;
    private int fieldTtlMinutes = 60;
    private int alertDebounceMinutes = 10;
    
    private Contracts contracts = new Contracts();
    private Async async = new Async();
    private Threshold threshold = new Threshold();
    private List<String> excludePaths = new ArrayList<>();

    public GhostDeployProperties() {
        excludePaths.add("/health");
        excludePaths.add("/actuator");
        excludePaths.add("/error");
        excludePaths.add("/ghostdeploy");
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getSamplingRate() { return samplingRate; }
    public void setSamplingRate(double samplingRate) { this.samplingRate = samplingRate; }

    public int getMaxBodySizeBytes() { return maxBodySizeBytes; }
    public void setMaxBodySizeBytes(int maxBodySizeBytes) { this.maxBodySizeBytes = maxBodySizeBytes; }

    public int getMaxEndpointsTracked() { return maxEndpointsTracked; }
    public void setMaxEndpointsTracked(int maxEndpointsTracked) { this.maxEndpointsTracked = maxEndpointsTracked; }

    public int getMaxFieldsPerEndpoint() { return maxFieldsPerEndpoint; }
    public void setMaxFieldsPerEndpoint(int maxFieldsPerEndpoint) { this.maxFieldsPerEndpoint = maxFieldsPerEndpoint; }

    public int getWarmupRequests() { return warmupRequests; }
    public void setWarmupRequests(int warmupRequests) { this.warmupRequests = warmupRequests; }

    public int getEndpointTtlMinutes() { return endpointTtlMinutes; }
    public void setEndpointTtlMinutes(int endpointTtlMinutes) { this.endpointTtlMinutes = endpointTtlMinutes; }

    public int getFieldTtlMinutes() { return fieldTtlMinutes; }
    public void setFieldTtlMinutes(int fieldTtlMinutes) { this.fieldTtlMinutes = fieldTtlMinutes; }

    public int getAlertDebounceMinutes() { return alertDebounceMinutes; }
    public void setAlertDebounceMinutes(int alertDebounceMinutes) { this.alertDebounceMinutes = alertDebounceMinutes; }

    public Threshold getThreshold() { return threshold; }
    public void setThreshold(Threshold threshold) { this.threshold = threshold; }

    public Contracts getContracts() { return contracts; }
    public void setContracts(Contracts contracts) { this.contracts = contracts; }

    public Async getAsync() { return async; }
    public void setAsync(Async async) { this.async = async; }

    public List<String> getExcludePaths() { return excludePaths; }
    public void setExcludePaths(List<String> excludePaths) { this.excludePaths = excludePaths; }

    public static class Contracts {
        private boolean enabled = false;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Async {
        private int queueCapacity = 1000;
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    public static class Threshold {
        private double fieldExpectedPresence = 0.90;
        private double fieldDrop = 0.20;
        private double ignoreFrequency = 0.10;
        private double nullRateSpike = 0.30;
        
        // Refined properties
        private double typeDriftSensitivity = 0.20; // 20%
        private int minSamples = 30; // min 30 samples
        private double minPresenceRate = 0.30; // 30% presence required before alert

        public double getFieldExpectedPresence() { return fieldExpectedPresence; }
        public void setFieldExpectedPresence(double fieldExpectedPresence) { this.fieldExpectedPresence = fieldExpectedPresence; }

        public double getFieldDrop() { return fieldDrop; }
        public void setFieldDrop(double fieldDrop) { this.fieldDrop = fieldDrop; }

        public double getIgnoreFrequency() { return ignoreFrequency; }
        public void setIgnoreFrequency(double ignoreFrequency) { this.ignoreFrequency = ignoreFrequency; }

        public double getNullRateSpike() { return nullRateSpike; }
        public void setNullRateSpike(double nullRateSpike) { this.nullRateSpike = nullRateSpike; }

        public double getTypeDriftSensitivity() { return typeDriftSensitivity; }
        public void setTypeDriftSensitivity(double typeDriftSensitivity) { this.typeDriftSensitivity = typeDriftSensitivity; }

        public int getMinSamples() { return minSamples; }
        public void setMinSamples(int minSamples) { this.minSamples = minSamples; }

        public double getMinPresenceRate() { return minPresenceRate; }
        public void setMinPresenceRate(double minPresenceRate) { this.minPresenceRate = minPresenceRate; }
    }
}
