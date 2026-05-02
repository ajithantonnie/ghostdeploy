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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Covers all remaining branches in GhostInterceptor:
 * - enabled=false in afterCompletion
 * - contentType null
 * - JSON content type but NOT a ContentCachingResponseWrapper
 * - sampling branch
 * - gzip=true with oversized body (skip size check)
 * - empty body in wrapper
 * - appName empty string fallback
 * - applicationContext.getId() returns non-null
 */
class GhostInterceptorBranchCoverageTest {

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
    void testAfterCompletionSkipsWhenDisabled() {
        properties.setEnabled(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.setAttribute("GhostDeployStartTime", System.currentTimeMillis() - 50);
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.afterCompletion(request, response, new Object(), null);

        verifyNoInteractions(baselineService);
    }

    @Test
    void testAfterCompletionSkipsWhenNoStartTime() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        // No START_TIME attribute set
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("application/json");

        interceptor.afterCompletion(request, response, new Object(), null);

        verifyNoInteractions(baselineService);
    }

    @Test
    void testAfterCompletionNullContentType() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.setAttribute("GhostDeployStartTime", System.currentTimeMillis() - 10);

        MockHttpServletResponse response = new MockHttpServletResponse();
        // contentType is null

        interceptor.afterCompletion(request, response, new Object(), null);

        verify(baselineService).processRequestStatsOnly(anyString(), anyLong(), anyInt());
        verify(baselineService, never()).processRequest(anyString(), anyLong(), anyInt(), any(), anyBoolean());
    }

    @Test
    void testAfterCompletionJsonButNotWrapper() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.setAttribute("GhostDeployStartTime", System.currentTimeMillis() - 10);

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("application/json");
        // NOT a ContentCachingResponseWrapper → falls through without calling any
        // baselineService method

        interceptor.afterCompletion(request, response, new Object(), null);

        // Neither processRequest nor processRequestStatsOnly should be called
        // (the branch exits the if block without an else when not a wrapper)
        verifyNoInteractions(baselineService);
    }

    @Test
    void testEmptyBodyInWrapperCallsStatsOnly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.setAttribute("GhostDeployStartTime", System.currentTimeMillis() - 10);

        MockHttpServletResponse rawResponse = new MockHttpServletResponse();
        rawResponse.setContentType("application/json");

        // Empty wrapper — no body written
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(rawResponse);

        interceptor.afterCompletion(request, wrapper, new Object(), null);

        verify(baselineService).processRequestStatsOnly(anyString(), anyLong(), anyInt());
        verify(baselineService, never()).processRequest(anyString(), anyLong(), anyInt(), any(), anyBoolean());
    }

    @Test
    void testGzipBodyNotSizeCheckedInInterceptor() throws Exception {
        // Gzip bodies skip the maxBodySize check in the interceptor (done in async)
        properties.setMaxBodySizeBytes(5); // tiny limit that would trigger for non-gzip

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.setAttribute("GhostDeployStartTime", System.currentTimeMillis() - 10);

        MockHttpServletResponse rawResponse = new MockHttpServletResponse();
        rawResponse.setContentType("application/json");
        rawResponse.addHeader("Content-Encoding", "gzip");

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(rawResponse);
        wrapper.getWriter().write("largebody");

        interceptor.afterCompletion(request, wrapper, new Object(), null);

        // Should call processRequest (not processRequestStatsOnly) because gzip
        // bypasses size check
        verify(baselineService).processRequest(anyString(), anyLong(), anyInt(), any(), eq(true));
    }

    @Test
    void testPreHandleSkipsWhenDisabled() {
        properties.setEnabled(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        assertNull(request.getAttribute("GhostDeployStartTime"));
    }

    @Test
    void testApplicationContextIdFallback() {
        when(environment.getProperty("spring.application.name")).thenReturn(""); // empty string
        when(applicationContext.getId()).thenReturn("ctx-id");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        request.setAttribute("GhostDeployStartTime", System.currentTimeMillis() - 10);

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType("text/plain");

        interceptor.afterCompletion(request, response, new Object(), null);

        verify(baselineService).processRequestStatsOnly(
                argThat(key -> key.startsWith("ctx-id:")), anyLong(), anyInt());
    }

    @Test
    void testSamplingRateLessThanOneIsApplied() {
        // Set sampling to 0% — every request should be skipped
        properties.setSamplingRate(0.0);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/data");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());

        // With sampling=0.0, all random values > 0.0 → always skipped
        assertNull(request.getAttribute("GhostDeployStartTime"));
    }
}
