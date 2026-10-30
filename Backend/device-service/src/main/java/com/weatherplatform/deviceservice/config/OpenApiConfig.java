package com.weatherplatform.deviceservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI deviceServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Smart Weather Monitoring — Device Service API")
                        .description("Microservice for registering, managing, and monitoring IoT weather station devices.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Weather Platform Engineering Team")
                                .email("engineering@weatherplatform.com"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
