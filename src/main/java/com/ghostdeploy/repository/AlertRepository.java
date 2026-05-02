package com.ghostdeploy.repository;

import com.ghostdeploy.model.AnomalyAlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AlertRepository extends JpaRepository<AnomalyAlertEntity, Long> {
}
