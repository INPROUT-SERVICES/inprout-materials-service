package br.com.inproutservices.inprout_materials_service.repositories;

import br.com.inproutservices.inprout_materials_service.entities.Solicitacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SolicitacaoRepository extends JpaRepository<Solicitacao, Long> {
}