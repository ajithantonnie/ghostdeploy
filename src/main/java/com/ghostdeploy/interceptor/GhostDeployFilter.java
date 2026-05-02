package com.ghostdeploy.interceptor;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;

public class GhostDeployFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Skip wrapping for known excluded paths
        String uri = httpRequest.getRequestURI();
        if (uri.startsWith("/actuator") || uri.startsWith("/health") || uri.startsWith("/error")) {
            chain.doFilter(request, response);
            return;
        }

        // Avoid double wrapping
        if (response instanceof ContentCachingResponseWrapper) {
            chain.doFilter(request, response);
            return;
        }

        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(httpResponse);
        
        try {
            chain.doFilter(request, responseWrapper);
        } finally {
            // Important: This must be called to actually copy the cached content to the real response
            responseWrapper.copyBodyToResponse();
        }
    }
}
