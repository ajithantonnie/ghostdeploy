package com.ghostdeploy.repository;

import com.ghostdeploy.model.StatsSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StatsRepository extends JpaRepository<StatsSnapshotEntity, Long> {
    Optional<StatsSnapshotEntity> findByEndpointKey(String endpointKey);
}
