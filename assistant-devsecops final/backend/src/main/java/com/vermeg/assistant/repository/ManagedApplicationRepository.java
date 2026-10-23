/**
 * Role : acceder aux applications surveillees persistees.
 * Contenu : definition de ManagedApplicationRepository et de ses responsabilites dans le backend.
 */

package com.vermeg.assistant.repository;

import com.vermeg.assistant.domain.ManagedApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ManagedApplicationRepository extends JpaRepository<ManagedApplication, Long> {

    Optional<ManagedApplication> findByCode(String code);

    boolean existsByCode(String code);

    List<ManagedApplication> findAllByOrderByNameAsc();

    List<ManagedApplication> findByActiveTrueOrderByNameAsc();
}
