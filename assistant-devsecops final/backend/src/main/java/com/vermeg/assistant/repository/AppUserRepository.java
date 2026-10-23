/**
 * Role : fournir l acces aux donnees persistees de AppUser via Spring Data.
 * Contenu : definition de AppUserRepository et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.repository;

import com.vermeg.assistant.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);
}
