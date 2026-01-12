package br.com.inproutservices.inprout_materials_service.repositories;

import br.com.inproutservices.inprout_materials_service.entities.Solicitacao;
import br.com.inproutservices.inprout_materials_service.enums.StatusSolicitacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SolicitacaoRepository extends JpaRepository<Solicitacao, Long> {

    // Busca todas as solicitações com um status específico
    List<Solicitacao> findByStatus(StatusSolicitacao status);

    // Busca histórico (tudo que não está pendente, por exemplo) - Opcional para o futuro
    List<Solicitacao> findBySolicitanteId(Long solicitanteId);
}