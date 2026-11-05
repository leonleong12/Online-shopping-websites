package com.onlineshop;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String uploadRoot = Path.of("uploads", "products").toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/products/**").addResourceLocations(uploadRoot);
        String siteUploadRoot = Path.of("uploads", "site").toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/site/**").addResourceLocations(siteUploadRoot);
    }
}
