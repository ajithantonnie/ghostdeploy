package com.ghostdeploy.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldStats {
    private String fieldName;
    private AtomicLong presenceCount = new AtomicLong(0);
    private AtomicLong nullCount = new AtomicLong(0);
    private long lastAccessedTimeMillis = System.currentTimeMillis();
    
    // Type tracking: e.g., STRING -> 50 times, NUMBER -> 2 times
    private ConcurrentHashMap<String, AtomicLong> typeFrequency = new ConcurrentHashMap<>();

    public FieldStats(String fieldName) {
        this.fieldName = fieldName;
    }

    public void record(FieldDetail detail) {
        presenceCount.incrementAndGet();
        lastAccessedTimeMillis = System.currentTimeMillis();
        if (detail.isNull()) {
            nullCount.incrementAndGet();
        }
        if (detail.getType() != null) {
            typeFrequency.computeIfAbsent(detail.getType(), k -> new AtomicLong(0)).incrementAndGet();
        }
    }
}
