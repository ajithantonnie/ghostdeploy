package com.ghostdeploy.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "ghost_deploy_stats_snapshot")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatsSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String endpointKey;

    @Column(nullable = false)
    private long totalRequestCount;

    @Column(nullable = false)
    private double averageResponseTime;

    @Column(columnDefinition = "TEXT")
    private String fieldStatsJson; // Storing the field presence map as JSON string to save space

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
