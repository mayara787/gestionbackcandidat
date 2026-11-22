package com.psytech.proctoring.config;

import com.psytech.proctoring.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor auth;
    private final ProctoringProperties props;

    public WebConfig(AuthInterceptor auth, ProctoringProperties props) {
        this.auth = auth;
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origins = props.corsOrigins() == null ? "*" : props.corsOrigins();
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins.split(","))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Service-Key");
    }
}
