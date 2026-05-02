package com.ghostdeploy.controller;

import com.ghostdeploy.engine.BaselineService;
import com.ghostdeploy.model.EndpointStats;
import com.ghostdeploy.model.FieldStats;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/ghostdeploy")
@ConditionalOnProperty(prefix = "ghostdeploy.contracts", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class ContractController {

    private final BaselineService baselineService;

    @GetMapping("/contracts")
    public Map<String, Object> getContracts() {
        Map<String, Object> snapshot = new HashMap<>();

        for (Map.Entry<String, EndpointStats> entry : baselineService.getMemoryStats().entrySet()) {
            String endpointKey = entry.getKey();
            EndpointStats stats = entry.getValue();
            long totalRequests = stats.getTotalRequestCount().get();

            Map<String, Object> fields = new HashMap<>();
            for (Map.Entry<String, FieldStats> fieldEntry : stats.getFieldStatsMap().entrySet()) {
                FieldStats fs = fieldEntry.getValue();

                long presenceCount = fs.getPresenceCount().get();
                if (totalRequests == 0 || presenceCount == 0) {
                    continue;
                }

                Map<String, Object> fieldDetails = new HashMap<>();
                double presenceRate = (double) presenceCount / totalRequests;
                fieldDetails.put("presenceRate", presenceRate);
                fieldDetails.put("types", fs.getTypeFrequency());

                fields.put(fieldEntry.getKey(), fieldDetails);
            }
            snapshot.put(endpointKey, fields);
        }

        return snapshot;
    }
}
