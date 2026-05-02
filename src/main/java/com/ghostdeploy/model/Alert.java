package com.ghostdeploy.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Alert {
    private String endpointKey;
    private String issueType; // FIELD_DROP, TYPE_DRIFT, RESPONSE_TIME_SPIKE, STATUS_CODE_DRIFT, EXTRA_FIELD, NULL_RATE_SPIKE
    private String description;
    private String severity; // HIGH, MEDIUM, LOW
    @Builder.Default
    private LocalDateTime detectedAt = LocalDateTime.now();
}
