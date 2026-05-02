package com.ghostdeploy.autoconfigure;

import com.ghostdeploy.config.GhostDeployProperties;
import com.ghostdeploy.interceptor.GhostDeployFilter;
import com.ghostdeploy.interceptor.GhostInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "ghostdeploy", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(GhostDeployProperties.class)
@EnableAsync
@EnableScheduling
@ComponentScan(basePackages = {
        "com.ghostdeploy.engine",
        "com.ghostdeploy.interceptor",
        "com.ghostdeploy.alerting",
        "com.ghostdeploy.controller"
})
@EnableJpaRepositories(basePackages = "com.ghostdeploy.repository")
@EntityScan(basePackages = "com.ghostdeploy.model")
public class GhostDeployAutoConfiguration implements WebMvcConfigurer {

    private final GhostInterceptor ghostInterceptor;
    private final GhostDeployProperties properties;

    public GhostDeployAutoConfiguration(@org.springframework.context.annotation.Lazy GhostInterceptor ghostInterceptor,
            GhostDeployProperties properties) {
        this.ghostInterceptor = ghostInterceptor;
        this.properties = properties;
    }

    @Bean
    public FilterRegistrationBean<GhostDeployFilter> ghostDeployFilterRegistration() {
        FilterRegistrationBean<GhostDeployFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new GhostDeployFilter());
        registrationBean.addUrlPatterns("/*");
        // Give it high precedence so it wraps early
        registrationBean.setOrder(1);
        return registrationBean;
    }

    @Bean
    @ConditionalOnMissingBean
    public MeterRegistry ghostDeployMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean(name = "ghostDeployTaskExecutor")
    public Executor ghostDeployTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(properties.getAsync().getQueueCapacity());
        executor.setThreadNamePrefix("GhostDeploy-");
        // Fail-safe: discard tasks silently if queue is full to protect main
        // application
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(ghostInterceptor)
                .addPathPatterns("/**");
    }
}
