package com.weatherplatform.analyticsservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI analyticsServiceOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Smart Weather Monitoring — Analytics Service API")
                .description("Historical analytics, daily/weekly/monthly aggregations for weather sensor data.")
                .version("1.0.0")
                .contact(new Contact().name("Weather Platform Engineering Team")));
    }
}
