/**
 * Role : configurer la documentation OpenAPI/Swagger des endpoints.
 * Contenu : definition de OpenApiConfig et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentation interactive de l'API : /swagger-ui.html */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI assistantOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Assistant DevSecOps - API")
                        .version("1.0.0")
                        .description("Analyse des logs, tests automatisés, vulnérabilités et recommandations IA"))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-API-Key")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
