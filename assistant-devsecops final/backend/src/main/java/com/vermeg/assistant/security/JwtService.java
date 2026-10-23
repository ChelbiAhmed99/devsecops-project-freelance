/**
 * Role : fournir le composant de securite JwtService, utilise pour l authentification, les autorisations ou la gestion des acces.
 * Contenu : definition de JwtService et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;

/** Émission et validation des jetons JWT (HMAC-SHA256). */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    static final String DEFAULT_DEV_SECRET = "dev-only-secret-change-me-please-0123456789abcdef";

    private final SecretKey key;
    private final long expirationMinutes;

    public JwtService(@Value("${assistant.jwt.secret}") String secret,
                      @Value("${assistant.jwt.expiration-minutes:480}") long expirationMinutes) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("assistant.jwt.secret doit contenir au moins 32 caractères");
        }
        if (DEFAULT_DEV_SECRET.equals(secret)) {
            log.warn("Secret JWT par défaut utilisé : à remplacer (variable JWT_SECRET) hors développement !");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

    public record IssuedToken(String token, LocalDateTime expiresAt) { }

    public record TokenClaims(String username, Role role) { }

    public IssuedToken issue(String username, Role role) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMinutes * 60_000L);
        String token = Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .issuer("assistant-devsecops")
                .issuedAt(now)
                .expiration(expiration)
                .signWith(key)
                .compact();
        return new IssuedToken(token, LocalDateTime.ofInstant(expiration.toInstant(), ZoneId.systemDefault()));
    }

    /** Renvoie les informations du jeton s'il est valide (signature + expiration), vide sinon. */
    public Optional<TokenClaims> validate(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            String roleName = claims.get("role", String.class);
            return Optional.of(new TokenClaims(claims.getSubject(), Role.valueOf(roleName)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
