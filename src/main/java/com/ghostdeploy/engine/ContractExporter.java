package com.ghostdeploy.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostdeploy.model.EndpointStats;
import com.ghostdeploy.model.FieldStats;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContractExporter {

    private final BaselineService baselineService;
    private final ObjectMapper objectMapper;

    /**
     * Exports the learned implicit schema baseline as a JSON string.
     */
    public String exportSchemaSnapshot() throws JsonProcessingException {
        Map<String, Map<String, Object>> snapshot = new HashMap<>();

        for (Map.Entry<String, EndpointStats> entry : baselineService.getMemoryStats().entrySet()) {
            String endpointKey = entry.getKey();
            EndpointStats stats = entry.getValue();
            long totalRequests = stats.getTotalRequestCount().get();

            Map<String, Object> schema = new HashMap<>();
            schema.put("totalRequests", totalRequests);
            schema.put("averageResponseTimeMs", stats.getAverageResponseTime());

            Map<String, Object> fields = new HashMap<>();
            for (Map.Entry<String, FieldStats> fieldEntry : stats.getFieldStatsMap().entrySet()) {
                FieldStats fs = fieldEntry.getValue();
                Map<String, Object> fieldDetails = new HashMap<>();
                
                double presenceRate = (double) fs.getPresenceCount().get() / totalRequests;
                double nullRate = fs.getPresenceCount().get() > 0 ? 
                        (double) fs.getNullCount().get() / fs.getPresenceCount().get() : 0.0;
                
                fieldDetails.put("presenceProbability", presenceRate);
                fieldDetails.put("nullProbability", nullRate);
                fieldDetails.put("typeDistribution", fs.getTypeFrequency());
                
                fields.put(fieldEntry.getKey(), fieldDetails);
            }
            schema.put("fields", fields);
            schema.put("statusCodes", stats.getStatusCodeFrequency());
            
            snapshot.put(endpointKey, schema);
        }

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
    }
}
