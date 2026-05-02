package com.ghostdeploy.alerting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghostdeploy.model.Alert;
import com.ghostdeploy.model.AnomalyAlertEntity;
import com.ghostdeploy.repository.AlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultAlertHandlerTest {

    @Mock
    private AlertRepository alertRepository;

    private DefaultAlertHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        handler = new DefaultAlertHandler(alertRepository, objectMapper);
    }

    @Test
    void testHandlePersistsAlert() {
        Alert alert = Alert.builder()
                .endpointKey("test-app:/api/users")
                .issueType("FIELD_DROP")
                .description("Field 'id' is missing. Historical presence: 95%")
                .severity("HIGH")
                .build();

        handler.handle(alert);

        ArgumentCaptor<AnomalyAlertEntity> captor = ArgumentCaptor.forClass(AnomalyAlertEntity.class);
        verify(alertRepository).save(captor.capture());

        AnomalyAlertEntity entity = captor.getValue();
        assertEquals("test-app:/api/users", entity.getEndpointKey());
        assertEquals("FIELD_DROP", entity.getIssueType());
        assertEquals("HIGH", entity.getSeverity());
        assertNotNull(entity.getDetectedAt());
    }

    @Test
    void testHandleSurvivesRepositoryException() {
        Alert alert = Alert.builder()
                .endpointKey("test-app:/api")
                .issueType("TYPE_DRIFT")
                .description("Type changed")
                .severity("MEDIUM")
                .build();

        // Use lenient to avoid strict stubbing error since the exception triggers the catch block
        lenient().doThrow(new RuntimeException("DB unavailable")).when(alertRepository).save(any());

        // Should not propagate exception
        assertDoesNotThrow(() -> handler.handle(alert));
    }

    @Test
    void testAnomalyAlertEntityConstructor() {
        AnomalyAlertEntity entity = new AnomalyAlertEntity(
                "app:/test", "FIELD_DROP", "desc", "HIGH"
        );
        assertEquals("app:/test", entity.getEndpointKey());
        assertEquals("FIELD_DROP", entity.getIssueType());
        assertEquals("desc", entity.getDescription());
        assertEquals("HIGH", entity.getSeverity());
        assertNotNull(entity.getDetectedAt());
    }

    @Test
    void testAnomalyAlertEntitySetters() {
        AnomalyAlertEntity entity = new AnomalyAlertEntity();
        entity.setId(42L);
        entity.setEndpointKey("app:/orders");
        entity.setIssueType("NULL_RATE_SPIKE");
        entity.setDescription("Null spike detected");
        entity.setSeverity("MEDIUM");
        LocalDateTime now = LocalDateTime.now();
        entity.setDetectedAt(now);

        assertEquals(42L, entity.getId());
        assertEquals("app:/orders", entity.getEndpointKey());
        assertEquals("NULL_RATE_SPIKE", entity.getIssueType());
        assertEquals("Null spike detected", entity.getDescription());
        assertEquals("MEDIUM", entity.getSeverity());
        assertEquals(now, entity.getDetectedAt());
    }
}
