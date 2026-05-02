package com.ghostdeploy.interceptor;

import com.ghostdeploy.config.GhostDeployProperties;
import com.ghostdeploy.engine.BaselineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

import static org.mockito.Mockito.*;

class GhostInterceptorTest {

    private BaselineService baselineService;
    private GhostDeployProperties properties;
    private Environment environment;
    private ApplicationContext applicationContext;
    private GhostInterceptor interceptor;

    @BeforeEach
    void setUp() {
        baselineService = mock(BaselineService.class);
        properties = new GhostDeployProperties();
        properties.setSamplingRate(1.0);
        environment = mock(Environment.class);
        applicationContext = mock(ApplicationContext.class);
        interceptor = new GhostInterceptor(baselineService, properties, environment, applicationContext);

        when(environment.getProperty("spring.application.name")).thenReturn("test-app");
    }

    @Test
    void testSkipExcludedPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assert (result);
        assert (request.getAttribute("GhostDeployStartTime") == null);
    }

    @Test
    void testProcessValidJsonResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.setAttribute("GhostDeployStartTime", System.currentTimeMillis() - 50); // 50ms ago

        MockHttpServletResponse rawResponse = new MockHttpServletResponse();
        rawResponse.setContentType("application/json");
        rawResponse.setStatus(200);

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(rawResponse);
        String json = "{\"id\": 123, \"name\": \"Ghost\"}";
        wrapper.getWriter().write(json);

        interceptor.afterCompletion(request, wrapper, new Object(), null);

        verify(baselineService).processRequest(
                eq("test-app:/api/data"),
                anyLong(),
                eq(200),
                argThat(payload -> payload != null && payload.length > 0),
                eq(false));
    }

    @Test
    void testProcessNonJsonResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.setAttribute("GhostDeployStartTime", System.currentTimeMillis() - 50);

        MockHttpServletResponse rawResponse = new MockHttpServletResponse();
        rawResponse.setContentType("text/plain");
        rawResponse.setStatus(200);

        interceptor.afterCompletion(request, rawResponse, new Object(), null);

        verify(baselineService).processRequestStatsOnly(eq("test-app:/api/data"), anyLong(), eq(200));
        verify(baselineService, never()).processRequest(anyString(), anyLong(), anyInt(), any(), anyBoolean());
    }

    @Test
    void testProcessGzipResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.setAttribute("GhostDeployStartTime", System.currentTimeMillis() - 50);

        MockHttpServletResponse rawResponse = new MockHttpServletResponse();
        rawResponse.setContentType("application/json");
        rawResponse.addHeader("Content-Encoding", "gzip");
        rawResponse.setStatus(200);

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(rawResponse);
        wrapper.getWriter().write("compresseddata");

        interceptor.afterCompletion(request, wrapper, new Object(), null);

        verify(baselineService).processRequest(
                eq("test-app:/api/data"),
                anyLong(),
                eq(200),
                any(),
                eq(true));
    }

    @Test
    void testProcessLargePayload() throws Exception {
        properties.setMaxBodySizeBytes(10); // Very small limit

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.setAttribute("GhostDeployStartTime", System.currentTimeMillis() - 50);

        MockHttpServletResponse rawResponse = new MockHttpServletResponse();
        rawResponse.setContentType("application/json");
        rawResponse.setStatus(200);

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(rawResponse);
        wrapper.getWriter().write("{\"large\": \"payload exceeding limit\"}");

        interceptor.afterCompletion(request, wrapper, new Object(), null);

        verify(baselineService).processRequestStatsOnly(eq("test-app:/api/data"), anyLong(), eq(200));
        verify(baselineService, never()).processRequest(anyString(), anyLong(), anyInt(), any(), anyBoolean());
    }

    @Test
    void testFallbackAppIdentity() throws Exception {
        when(environment.getProperty("spring.application.name")).thenReturn(null);
        when(applicationContext.getId()).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.setAttribute("GhostDeployStartTime", System.currentTimeMillis() - 50);

        MockHttpServletResponse rawResponse = new MockHttpServletResponse();
        rawResponse.setContentType("text/plain");

        interceptor.afterCompletion(request, rawResponse, new Object(), null);

        // Verify it didn't use test-app
        verify(baselineService).processRequestStatsOnly(argThat(key -> !key.startsWith("test-app:") && key.endsWith(":/api/data")), anyLong(), anyInt());
    }
}
