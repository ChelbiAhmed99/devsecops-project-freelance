/**
 * Role : gerer les operations metier liees aux comptes utilisateurs.
 * Contenu : definition de UserService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.service;

import com.vermeg.assistant.domain.AppUser;
import com.vermeg.assistant.model.ApiDtos;
import com.vermeg.assistant.repository.AppUserRepository;
import com.vermeg.assistant.security.CurrentUser;
import com.vermeg.assistant.security.JwtService;
import com.vermeg.assistant.security.LoginAttemptService;
import com.vermeg.assistant.security.Role;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/** Authentification et gestion des comptes utilisateurs. */
@Service
public class UserService {

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttempts;
    private final AuditService auditService;

    public UserService(AppUserRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       LoginAttemptService loginAttempts, AuditService auditService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttempts = loginAttempts;
        this.auditService = auditService;
    }

    /** Pas de @Transactional : l'audit d'un échec doit survivre à l'exception levée. */
    public ApiDtos.LoginResponse login(ApiDtos.LoginRequest request) {
        String username = request.username() == null ? "" : request.username().trim();
        if (loginAttempts.isLocked(username)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Compte temporairement verrouillé suite à plusieurs échecs. Réessayez dans quelques minutes.");
        }
        AppUser user = repository.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null || !user.isEnabled() || request.password() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginAttempts.onFailure(username);
            auditService.recordAs(username, "LOGIN_FAILED", username, null);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identifiants invalides");
        }
        loginAttempts.onSuccess(username);
        user.setLastLoginAt(LocalDateTime.now());
        repository.save(user);
        JwtService.IssuedToken token = jwtService.issue(user.getUsername(), user.getRole());
        auditService.recordAs(user.getUsername(), "LOGIN", user.getUsername(), null);
        return new ApiDtos.LoginResponse(token.token(), token.expiresAt(), toView(user));
    }

    @Transactional(readOnly = true)
    public ApiDtos.UserView me() {
        return repository.findByUsernameIgnoreCase(CurrentUser.username()).map(UserService::toView)
                .orElseThrow(() -> new NoSuchElementException("Utilisateur introuvable"));
    }

    @Transactional
    public void changePassword(ApiDtos.ChangePasswordRequest request) {
        AppUser user = repository.findByUsernameIgnoreCase(CurrentUser.username())
                .orElseThrow(() -> new NoSuchElementException("Utilisateur introuvable"));
        if (request.currentPassword() == null || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Mot de passe actuel incorrect");
        }
        validatePassword(request.newPassword());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        repository.save(user);
        auditService.record("PASSWORD_CHANGED", user.getUsername(), null);
    }

    @Transactional(readOnly = true)
    public List<ApiDtos.UserView> list() {
        return repository.findAll().stream().map(UserService::toView).toList();
    }

    @Transactional
    public ApiDtos.UserView create(ApiDtos.UserUpsertRequest request) {
        if (request.username() == null || !request.username().matches("[A-Za-z0-9._-]{3,80}")) {
            throw new IllegalArgumentException("Nom d'utilisateur invalide (3 à 80 caractères : lettres, chiffres, . _ -)");
        }
        if (repository.existsByUsernameIgnoreCase(request.username())) {
            throw new IllegalStateException("Ce nom d'utilisateur existe déjà");
        }
        validatePassword(request.password());
        AppUser user = new AppUser(request.username().trim(), passwordEncoder.encode(request.password()),
                request.fullName(), request.email(), parseRole(request.role()));
        repository.save(user);
        auditService.record("USER_CREATED", user.getUsername(), "rôle=" + user.getRole());
        return toView(user);
    }

    @Transactional
    public ApiDtos.UserView update(Long id, ApiDtos.UserUpsertRequest request) {
        AppUser user = repository.findById(id).orElseThrow(() -> new NoSuchElementException("Utilisateur introuvable"));
        boolean self = user.getUsername().equalsIgnoreCase(CurrentUser.username());
        if (request.fullName() != null) user.setFullName(request.fullName());
        if (request.email() != null) user.setEmail(request.email());
        if (request.role() != null) {
            Role role = parseRole(request.role());
            if (self && role != Role.ADMIN) {
                throw new IllegalArgumentException("Vous ne pouvez pas retirer votre propre rôle administrateur");
            }
            user.setRole(role);
        }
        if (request.enabled() != null) {
            if (self && !request.enabled()) {
                throw new IllegalArgumentException("Vous ne pouvez pas désactiver votre propre compte");
            }
            user.setEnabled(request.enabled());
        }
        if (request.password() != null && !request.password().isBlank()) {
            validatePassword(request.password());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        repository.save(user);
        auditService.record("USER_UPDATED", user.getUsername(), "rôle=" + user.getRole() + ", actif=" + user.isEnabled());
        return toView(user);
    }

    @Transactional
    public void delete(Long id) {
        AppUser user = repository.findById(id).orElseThrow(() -> new NoSuchElementException("Utilisateur introuvable"));
        if (user.getUsername().equalsIgnoreCase(CurrentUser.username())) {
            throw new IllegalArgumentException("Vous ne pouvez pas supprimer votre propre compte");
        }
        repository.delete(user);
        auditService.record("USER_DELETED", user.getUsername(), null);
    }

    private Role parseRole(String raw) {
        try {
            Role role = Role.valueOf(raw == null ? "DEVELOPER" : raw.trim().toUpperCase());
            if (role == Role.CI) throw new IllegalArgumentException("Le rôle CI est réservé au pipeline (clé d'API)");
            return role;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Rôle invalide : DEVELOPER, QUALITY_MANAGER ou ADMIN");
        }
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 8 || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new IllegalArgumentException("Le mot de passe doit contenir au moins 8 caractères, dont une lettre et un chiffre");
        }
    }

    public static ApiDtos.UserView toView(AppUser u) {
        return new ApiDtos.UserView(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getRole().name(),
                u.isEnabled(), u.getCreatedAt(), u.getLastLoginAt());
    }
}
