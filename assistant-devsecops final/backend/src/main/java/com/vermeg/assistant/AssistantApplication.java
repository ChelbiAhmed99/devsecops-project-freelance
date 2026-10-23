/**
 * Role : fournir le point d entree Spring Boot qui demarre le service backend.
 * Contenu : definition de AssistantApplication et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Point d'entrée de l'assistant DevSecOps : orchestration des briques logs,
 * tests, sécurité et IA, exposées via une API REST sécurisée (JWT).
 */
@SpringBootApplication
@EnableScheduling
public class AssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }
}
