package com.ghostdeploy.integration;

import com.ghostdeploy.engine.BaselineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ghostdeploy.enabled=true",
        "ghostdeploy.samplingRate=1.0",
        "spring.application.name=test-app"
})
@AutoConfigureMockMvc
class GhostDeployIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BaselineService baselineService;

    @Test
    void testInterceptorAndFilterCaptureResponse() throws Exception {
        mockMvc.perform(get("/api/test")
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Baseline service is async, we wait briefly
        Thread.sleep(200);

        // Verify baseline recorded something
        boolean hasStats = baselineService.getMemoryStats().containsKey("test-app:/api/test");
        assertTrue(hasStats, "Baseline should have recorded stats for the endpoint");
    }

    @SpringBootApplication
    static class TestApplication {
        @RestController
        static class TestController {
            @GetMapping("/api/test")
            public String testEndpoint() {
                return "{\"user\": {\"id\": 1, \"name\": \"Ghost\"}}";
            }
        }
    }
}
