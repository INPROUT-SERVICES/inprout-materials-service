package br.com.inproutservices.inprout_materials_service.repositories;

import br.com.inproutservices.inprout_materials_service.entities.Solicitacao;
import br.com.inproutservices.inprout_materials_service.enums.StatusSolicitacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SolicitacaoRepository extends JpaRepository<Solicitacao, Long> {

    // Busca todas as solicitações com um status específico
    List<Solicitacao> findByStatus(StatusSolicitacao status);

    // Busca histórico (tudo que não está pendente, por exemplo) - Opcional para o futuro
    List<Solicitacao> findBySolicitanteId(Long solicitanteId);

    @Query("SELECT DISTINCT s FROM Solicitacao s JOIN FETCH s.itens i WHERE i.statusItem = 'PENDENTE'")
    List<Solicitacao> findAllPendentesAdmin();

    List<Solicitacao> findByStatusAndSegmentoIdIn(StatusSolicitacao status, List<Long> segmentoIds);

    // 1. Para ADMIN/CONTROLLER (Filtro apenas por data)
    List<Solicitacao> findByDataSolicitacaoBetween(LocalDateTime inicio, LocalDateTime fim);

    // 2. Para COORDENADOR (Filtro por Data E Segmento)
    List<Solicitacao> findByDataSolicitacaoBetweenAndSegmentoIdIn(LocalDateTime inicio, LocalDateTime fim, List<Long> segmentoIds);

    // 3. Para SOLICITANTE COMUM (Filtro por Data E Solicitante)
    List<Solicitacao> findByDataSolicitacaoBetweenAndSolicitanteId(LocalDateTime inicio, LocalDateTime fim, Long solicitanteId);
}