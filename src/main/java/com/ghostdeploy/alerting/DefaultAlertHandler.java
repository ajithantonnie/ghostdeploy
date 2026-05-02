package com.ghostdeploy.alerting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostdeploy.model.Alert;
import com.ghostdeploy.model.AnomalyAlertEntity;
import com.ghostdeploy.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DefaultAlertHandler implements AlertHandler {

    private static final Logger log = LoggerFactory.getLogger("GhostDeployAlerts");
    private final AlertRepository alertRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void handle(Alert alert) {
        try {
            // Structured JSON logging
            log.warn(objectMapper.writeValueAsString(alert));
            
            // Persist to DB
            AnomalyAlertEntity entity = new AnomalyAlertEntity(
                    alert.getEndpointKey(),
                    alert.getIssueType(),
                    alert.getDescription(),
                    alert.getSeverity()
            );
            entity.setDetectedAt(alert.getDetectedAt());
            alertRepository.save(entity);
            
        } catch (Exception e) {
            log.error("Failed to handle GhostDeploy alert: {}", alert, e);
        }
    }
}
