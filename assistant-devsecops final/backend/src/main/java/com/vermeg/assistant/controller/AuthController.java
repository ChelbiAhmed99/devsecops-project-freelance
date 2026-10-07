package com.vermeg.assistant.controller;

import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * - POST /api/auth/login            -> jeton JWT
 * - GET  /api/auth/me               -> profil de l'utilisateur connecté
 * - POST /api/auth/change-password  -> changement de mot de passe
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public ApiDtos.LoginResponse login(@RequestBody ApiDtos.LoginRequest request) {
        return userService.login(request);
    }

    @GetMapping("/me")
    public ApiDtos.UserView me() {
        return userService.me();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(@RequestBody ApiDtos.ChangePasswordRequest request) {
        userService.changePassword(request);
        return ResponseEntity.ok(Map.of("message", "Mot de passe modifié"));
    }
}
