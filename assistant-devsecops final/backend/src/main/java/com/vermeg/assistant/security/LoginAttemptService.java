package com.vermeg.assistant.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Protection anti force brute : verrouillage temporaire après N échecs de connexion. */
@Service
public class LoginAttemptService {

    private final int maxAttempts;
    private final int lockMinutes;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService(@Value("${assistant.login.max-attempts:5}") int maxAttempts,
                               @Value("${assistant.login.lock-minutes:5}") int lockMinutes) {
        this.maxAttempts = maxAttempts;
        this.lockMinutes = lockMinutes;
    }

    private record Attempts(int count, LocalDateTime lockedUntil) { }

    public boolean isLocked(String username) {
        Attempts a = attempts.get(key(username));
        return a != null && a.lockedUntil() != null && a.lockedUntil().isAfter(LocalDateTime.now());
    }

    public void onFailure(String username) {
        attempts.compute(key(username), (k, current) -> {
            int count = (current == null ? 0 : current.count()) + 1;
            LocalDateTime lock = count >= maxAttempts ? LocalDateTime.now().plusMinutes(lockMinutes) : null;
            return new Attempts(lock != null ? 0 : count, lock);
        });
    }

    public void onSuccess(String username) {
        attempts.remove(key(username));
    }

    private String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
