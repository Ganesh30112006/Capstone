package com.weatherplatform.authservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI authServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Smart Weather Monitoring — Auth Service API")
                        .description("Authentication and authorization service with JWT-based security.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Weather Platform Engineering Team")
                                .email("engineering@weatherplatform.com")));
    }
}
