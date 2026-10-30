package com.weatherplatform.sensorservice.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sensorServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Smart Weather Monitoring — Sensor Service API")
                        .description("Microservice for ingesting, querying, and analyzing IoT environmental sensor measurements.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Weather Platform Engineering Team")
                                .email("engineering@weatherplatform.com"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .externalDocs(new ExternalDocumentation()
                        .description("Smart Weather Platform Architecture Documentation")
                        .url("https://github.com/weatherplatform/docs"));
    }
}
