/**
 * Role : exposer les endpoints REST lies aux comptes utilisateurs et deleguer leur traitement aux services.
 * Contenu : definition de UserController et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Gestion des comptes (rôle ADMIN, restriction posée dans SecurityConfig). */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<ApiDtos.UserView> list() {
        return userService.list();
    }

    @PostMapping
    public ApiDtos.UserView create(@RequestBody ApiDtos.UserUpsertRequest request) {
        return userService.create(request);
    }

    @PutMapping("/{id}")
    public ApiDtos.UserView update(@PathVariable Long id, @RequestBody ApiDtos.UserUpsertRequest request) {
        return userService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
