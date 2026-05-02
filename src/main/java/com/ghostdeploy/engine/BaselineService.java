package com.ghostdeploy.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostdeploy.config.GhostDeployProperties;
import com.ghostdeploy.model.EndpointStats;
import com.ghostdeploy.model.FieldDetail;
import com.ghostdeploy.model.FieldStats;
import com.ghostdeploy.model.StatsSnapshotEntity;
import com.ghostdeploy.repository.StatsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Service
@RequiredArgsConstructor
public class BaselineService {

    private static final Logger log = LoggerFactory.getLogger(BaselineService.class);

    private final Map<String, EndpointStats> memoryStats = new ConcurrentHashMap<>();
    private final StatsRepository statsRepository;
    private final AnomalyDetector anomalyDetector;
    private final ObjectMapper objectMapper;
    private final GhostDeployProperties properties;
    private final MeterRegistry meterRegistry;

    private static final Pattern UUID_OR_NUMERIC_PATTERN = Pattern.compile("^[0-9]+$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public Map<String, EndpointStats> getMemoryStats() {
        return memoryStats;
    }

    @PostConstruct
    public void init() {
        meterRegistry.gauge("ghostdeploy.endpoints.tracked", memoryStats, Map::size);

        try {
            statsRepository.findAll().forEach(entity -> {
                EndpointStats stats = new EndpointStats(entity.getEndpointKey());
                stats.getTotalRequestCount().set(entity.getTotalRequestCount());
                stats.getCumulativeResponseTime().set((long) (entity.getTotalRequestCount() * entity.getAverageResponseTime()));
                
                try {
                    Map<String, Long> fieldCounts = objectMapper.readValue(entity.getFieldStatsJson(), new TypeReference<Map<String, Long>>() {});
                    fieldCounts.forEach((field, count) -> {
                        FieldStats fs = new FieldStats(field);
                        fs.getPresenceCount().set(count);
                        stats.getFieldStatsMap().put(field, fs);
                    });
                } catch (JsonProcessingException e) {
                    log.error("Failed to parse field stats JSON for endpoint {}", entity.getEndpointKey(), e);
                }
                
                memoryStats.put(entity.getEndpointKey(), stats);
            });
            log.info("GhostDeploy BaselineService initialized with {} endpoints from database.", memoryStats.size());
        } catch (Exception e) {
            log.warn("Failed to load Baseline stats from DB. Starting fresh.", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        persistSnapshots();
    }

    @Async("ghostDeployTaskExecutor")
    public void processRequestStatsOnly(String endpointKey, long responseTime, int statusCode) {
        processInternal(endpointKey, responseTime, statusCode, Collections.emptyList());
    }

    @Async("ghostDeployTaskExecutor")
    public void processRequest(String endpointKey, long responseTime, int statusCode, byte[] payload, boolean isGzip) {
        List<FieldDetail> extractedFields = Collections.emptyList();
        try {
            byte[] decompressed = payload;
            if (isGzip) {
                decompressed = decompressGzip(payload, properties.getMaxBodySizeBytes());
                if (decompressed == null) {
                    // Exceeded max size during decompression
                    log.debug("GZIP payload exceeded max body size for {}. Proceeding without field extraction.", endpointKey);
                    processInternal(endpointKey, responseTime, statusCode, Collections.emptyList());
                    return;
                }
            }

            if (decompressed.length > 0) {
                JsonNode rootNode = objectMapper.readTree(decompressed);
                extractedFields = new ArrayList<>();
                extractFields(rootNode, "", extractedFields);
            }
        } catch (Exception e) {
            log.debug("Failed to process payload for endpoint {}", endpointKey, e);
        }

        processInternal(endpointKey, responseTime, statusCode, extractedFields);
    }

    private byte[] decompressGzip(byte[] compressed, int maxBytes) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             GZIPInputStream gis = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int len;
            int total = 0;
            while ((len = gis.read(buffer)) > 0) {
                total += len;
                if (total > maxBytes) {
                    return null; // Exceeded limit
                }
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }

    private void processInternal(String endpointKey, long responseTime, int statusCode, List<FieldDetail> extractedFields) {
        meterRegistry.counter("ghostdeploy.requests.processed", "endpoint", endpointKey).increment();

        // Evict if max endpoints reached
        if (memoryStats.size() >= properties.getMaxEndpointsTracked() && !memoryStats.containsKey(endpointKey)) {
            evictOldestEndpoint();
        }

        EndpointStats stats = memoryStats.computeIfAbsent(endpointKey, EndpointStats::new);
        
        if (!extractedFields.isEmpty()) {
            updateSchemaFingerprint(stats, extractedFields);
            anomalyDetector.detectAnomalies(stats, extractedFields, responseTime, statusCode);
        }

        stats.incrementRequest(responseTime, statusCode);
        
        for (FieldDetail detail : extractedFields) {
            if (stats.getFieldStatsMap().size() >= properties.getMaxFieldsPerEndpoint() && !stats.getFieldStatsMap().containsKey(detail.getFieldName())) {
                evictOldestField(stats);
                if (stats.getFieldStatsMap().size() >= properties.getMaxFieldsPerEndpoint()) {
                    continue; // Skip if still full
                }
            }
            stats.getFieldStatsMap()
                    .computeIfAbsent(detail.getFieldName(), k -> new FieldStats(detail.getFieldName()))
                    .record(detail);
        }
    }

    private void updateSchemaFingerprint(EndpointStats stats, List<FieldDetail> extractedFields) {
        // Canonical sorted representation of field paths and types
        String currentFingerprint = extractedFields.stream()
                .map(f -> f.getFieldName() + ":" + f.getType())
                .sorted()
                .collect(Collectors.joining("|"));
        
        // Fast non-cryptographic hash (MurmurHash3 32-bit equivalent)
        int hash = murmurHash3_x86_32(currentFingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, currentFingerprint.length(), 0x9747b28c);
        String hashHex = Integer.toHexString(hash);
        
        if (!hashHex.equals(stats.getSchemaFingerprint())) {
            stats.setSchemaFingerprint(hashHex);
        }
    }

    private static int murmurHash3_x86_32(byte[] data, int offset, int len, int seed) {
        int c1 = 0xcc9e2d51;
        int c2 = 0x1b873593;

        int h1 = seed;
        int roundedEnd = offset + (len & 0xfffffffc);  // round down to 4 byte block

        for (int i = offset; i < roundedEnd; i += 4) {
            // little endian load order
            int k1 = (data[i] & 0xff) | ((data[i + 1] & 0xff) << 8) | ((data[i + 2] & 0xff) << 16) | (data[i + 3] << 24);
            k1 *= c1;
            k1 = (k1 << 15) | (k1 >>> 17);  // ROTL32(k1,15);
            k1 *= c2;

            h1 ^= k1;
            h1 = (h1 << 13) | (h1 >>> 19);  // ROTL32(h1,13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        switch(len & 0x03) {
            case 3:
                k1 = (data[roundedEnd + 2] & 0xff) << 16;
                // fallthrough
            case 2:
                k1 |= (data[roundedEnd + 1] & 0xff) << 8;
                // fallthrough
            case 1:
                k1 |= (data[roundedEnd] & 0xff);
                k1 *= c1;
                k1 = (k1 << 15) | (k1 >>> 17);  // ROTL32(k1,15);
                k1 *= c2;
                h1 ^= k1;
        }

        h1 ^= len;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;

        return h1;
    }

    @Scheduled(fixedRateString = "300000") // Every 5 minutes
    public void scheduledTtlSweeper() {
        long currentTime = System.currentTimeMillis();
        long endpointTtlMillis = properties.getEndpointTtlMinutes() * 60 * 1000L;
        long fieldTtlMillis = properties.getFieldTtlMinutes() * 60 * 1000L;

        memoryStats.entrySet().removeIf(entry -> (currentTime - entry.getValue().getLastAccessedTimeMillis()) > endpointTtlMillis);

        for (EndpointStats stats : memoryStats.values()) {
            stats.getFieldStatsMap().entrySet().removeIf(entry -> (currentTime - entry.getValue().getLastAccessedTimeMillis()) > fieldTtlMillis);
        }
    }

    private void evictOldestEndpoint() {
        memoryStats.entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().getLastAccessedTimeMillis()))
                .ifPresent(e -> memoryStats.remove(e.getKey()));
    }

    private void evictOldestField(EndpointStats stats) {
        stats.getFieldStatsMap().entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().getLastAccessedTimeMillis()))
                .ifPresent(e -> stats.getFieldStatsMap().remove(e.getKey()));
    }

    private void extractFields(JsonNode node, String prefix, List<FieldDetail> fields) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fieldsIterator = node.fields();
            while (fieldsIterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = fieldsIterator.next();
                String keySegment = entry.getKey();
                
                if (UUID_OR_NUMERIC_PATTERN.matcher(keySegment).matches()) {
                    keySegment = "*";
                }
                
                String key = prefix.isEmpty() ? keySegment : prefix + "." + keySegment;
                JsonNode child = entry.getValue();
                
                String type = determineType(child);
                boolean isNull = child.isNull();
                
                fields.add(new FieldDetail(key, type, null, isNull));
                extractFields(child, key, fields);
            }
        } else if (node.isArray()) {
            String arrayPrefix = prefix + "[*]";
            if (node.size() > 0) {
                int elementsToInspect = Math.min(node.size(), 3);
                for (int i = 0; i < elementsToInspect; i++) {
                    extractFields(node.get(i), arrayPrefix, fields);
                }
            }
        }
    }

    private String determineType(JsonNode node) {
        if (node.isTextual()) return "STRING";
        if (node.isNumber()) return "NUMBER";
        if (node.isBoolean()) return "BOOLEAN";
        if (node.isNull()) return "NULL";
        if (node.isArray()) return "ARRAY";
        if (node.isObject()) return "OBJECT";
        return "UNKNOWN";
    }

    public void persistSnapshots() {
        memoryStats.forEach((key, stats) -> {
            StatsSnapshotEntity entity = statsRepository.findByEndpointKey(key)
                    .orElse(new StatsSnapshotEntity());
            
            entity.setEndpointKey(key);
            entity.setTotalRequestCount(stats.getTotalRequestCount().get());
            entity.setAverageResponseTime(stats.getAverageResponseTime());
            
            Map<String, Long> fieldCountMap = new ConcurrentHashMap<>();
            stats.getFieldStatsMap().forEach((k, v) -> fieldCountMap.put(k, v.getPresenceCount().get()));
            
            try {
                entity.setFieldStatsJson(objectMapper.writeValueAsString(fieldCountMap));
                statsRepository.save(entity);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize field stats for endpoint {}", key, e);
            }
        });
        log.info("Persisted GhostDeploy snapshots to database.");
    }
}
