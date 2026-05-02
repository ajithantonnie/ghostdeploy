package com.ghostdeploy.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EndpointStats {
    private String endpointKey; // applicationName:endpoint
    private AtomicLong totalRequestCount = new AtomicLong(0);
    private AtomicLong cumulativeResponseTime = new AtomicLong(0);
    private long lastAccessedTimeMillis = System.currentTimeMillis();
    private String schemaFingerprint = "";
    
    private ConcurrentHashMap<String, FieldStats> fieldStatsMap = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, AtomicLong> statusCodeFrequency = new ConcurrentHashMap<>();

    public EndpointStats(String endpointKey) {
        this.endpointKey = endpointKey;
    }

    public void incrementRequest(long responseTime, int statusCode) {
        totalRequestCount.incrementAndGet();
        cumulativeResponseTime.addAndGet(responseTime);
        lastAccessedTimeMillis = System.currentTimeMillis();
        statusCodeFrequency.computeIfAbsent(statusCode, k -> new AtomicLong(0)).incrementAndGet();
    }

    public double getAverageResponseTime() {
        long count = totalRequestCount.get();
        return count == 0 ? 0 : (double) cumulativeResponseTime.get() / count;
    }
}
