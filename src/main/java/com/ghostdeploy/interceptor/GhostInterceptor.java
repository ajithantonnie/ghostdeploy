package com.ghostdeploy.interceptor;

import com.ghostdeploy.config.GhostDeployProperties;
import com.ghostdeploy.engine.BaselineService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class GhostInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GhostInterceptor.class);

    private final BaselineService baselineService;
    private final GhostDeployProperties properties;
    private final Environment environment;
    private final ApplicationContext applicationContext;

    private static final String START_TIME_ATTR = "GhostDeployStartTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled() || isExcluded(request.getRequestURI())) {
            return true;
        }

        // Sampling
        if (properties.getSamplingRate() < 1.0) {
            if (ThreadLocalRandom.current().nextDouble() > properties.getSamplingRate()) {
                return true; // Skip profiling this request
            }
        }

        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!properties.isEnabled()) {
            return;
        }

        Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
        if (startTime == null) {
            return; // Was skipped or excluded
        }

        long responseTime = System.currentTimeMillis() - startTime;
        int status = response.getStatus();
        String uri = request.getRequestURI();
        String endpointKey = buildEndpointKey(uri);

        String contentType = response.getContentType();
        if (contentType != null && contentType.contains("application/json")) {
            if (response instanceof ContentCachingResponseWrapper) {
                ContentCachingResponseWrapper wrapper = (ContentCachingResponseWrapper) response;
                
                String encoding = wrapper.getHeader("Content-Encoding");
                boolean isGzip = encoding != null && encoding.contains("gzip");

                byte[] rawContent = wrapper.getContentAsByteArray();
                
                // For non-gzip, we can check size here. For gzip, we check during decompression in async.
                if (!isGzip && rawContent.length > properties.getMaxBodySizeBytes()) {
                    log.debug("Response body size {} exceeds max. Skipping deep inspection.", rawContent.length);
                    baselineService.processRequestStatsOnly(endpointKey, responseTime, status);
                    return;
                }

                if (rawContent.length > 0) {
                    // Pass copied raw data to async service
                    baselineService.processRequest(endpointKey, responseTime, status, rawContent, isGzip);
                } else {
                    baselineService.processRequestStatsOnly(endpointKey, responseTime, status);
                }
            }
        } else {
            // Not JSON, just record stats
            baselineService.processRequestStatsOnly(endpointKey, responseTime, status);
        }
    }

    private static final String FALLBACK_ID = java.util.UUID.randomUUID().toString();

    private String buildEndpointKey(String uri) {
        String appName = environment.getProperty("spring.application.name");
        if (appName == null || appName.isEmpty()) {
            appName = applicationContext.getId();
            if (appName == null || appName.isEmpty()) {
                appName = FALLBACK_ID;
            }
        }
        return appName + ":" + uri;
    }

    private boolean isExcluded(String uri) {
        for (String excluded : properties.getExcludePaths()) {
            if (uri.startsWith(excluded)) {
                return true;
            }
        }
        return false;
    }
}
