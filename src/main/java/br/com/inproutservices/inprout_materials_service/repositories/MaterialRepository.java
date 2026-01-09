package br.com.inproutservices.inprout_materials_service.repositories;

import br.com.inproutservices.inprout_materials_service.entities.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MaterialRepository extends JpaRepository<Material, Long> {
    Optional<Material> findByCodigo(String codigo);

    @Query("SELECT m FROM Material m LEFT JOIN FETCH m.entradas WHERE m.id = :id")
    Optional<Material> findByIdWithEntradas(@Param("id") Long id);
}