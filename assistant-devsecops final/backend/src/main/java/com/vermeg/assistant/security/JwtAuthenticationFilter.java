/**
 * Role : fournir le composant de securite JwtAuthenticationFilter, utilise pour l authentification, les autorisations ou la gestion des acces.
 * Contenu : definition de JwtAuthenticationFilter et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Lit le jeton "Authorization: Bearer ..." et peuple le contexte de sécurité.
 * Exception : le flux SSE (/api/events/stream) accepte le jeton en paramètre
 * "token", car l'API EventSource du navigateur ne permet pas d'ajouter d'en-tête.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String SSE_PATH = "/api/events/stream";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            jwtService.validate(token).ifPresent(claims -> {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        claims.username(), null,
                        List.of(new SimpleGrantedAuthority(claims.role().authority())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        }
        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        if (SSE_PATH.equals(request.getRequestURI())) {
            String param = request.getParameter("token");
            if (param != null && !param.isBlank()) {
                return param;
            }
        }
        return null;
    }
}
