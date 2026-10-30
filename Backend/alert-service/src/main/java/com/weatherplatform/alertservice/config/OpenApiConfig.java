package com.weatherplatform.alertservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI alertServiceOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Smart Weather Monitoring — Alert Service API")
                .description("Manage alert rules and view triggered alerts for weather sensors.")
                .version("1.0.0")
                .contact(new Contact().name("Weather Platform Engineering Team")));
    }
}
