package com.erp.apigateway.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ERP API Gateway - Automotive SaaS")
                        .version("1.0.0")
                        .description("Borde perimetral para validación JWKS, aislamiento multi-inquilino, control de tráfico y enrutamiento.")
                        .contact(new Contact()
                                .name("ERP Core Architecture Team")
                                .email("dev@erp-automotive.pe"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://erp-automotive.pe")));
    }
}
