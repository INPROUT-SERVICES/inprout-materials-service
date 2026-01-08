package br.com.inproutservices.inprout_materials_service.repositories;

import br.com.inproutservices.inprout_materials_service.entities.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MaterialRepository extends JpaRepository<Material, Long> {
}