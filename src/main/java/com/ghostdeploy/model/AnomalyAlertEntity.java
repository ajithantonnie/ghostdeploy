package com.ghostdeploy.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "ghost_deploy_anomaly_alerts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyAlertEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String endpointKey;

    @Column(nullable = false)
    private String issueType; // FIELD_DROP, RESPONSE_TIME_SPIKE

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String severity; // HIGH, MEDIUM, LOW

    @Column(nullable = false)
    private LocalDateTime detectedAt = LocalDateTime.now();

    public AnomalyAlertEntity(String endpointKey, String issueType, String description, String severity) {
        this.endpointKey = endpointKey;
        this.issueType = issueType;
        this.description = description;
        this.severity = severity;
        this.detectedAt = LocalDateTime.now();
    }
}
