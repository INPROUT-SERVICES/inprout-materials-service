package br.com.inproutservices.inprout_materials_service.dtos.response;

import br.com.inproutservices.inprout_materials_service.entities.ItemSolicitacao;
import br.com.inproutservices.inprout_materials_service.enums.StatusSolicitacao;
import java.time.LocalDateTime;
import java.util.List;

public record SolicitacaoResponseDTO(
        Long id,
        LocalDateTime dataSolicitacao,
        String justificativa,
        StatusSolicitacao status,
        String nomeSolicitante, // Já facilitamos mandando o nome direto
        OsDTO os,               // O frontend espera este objeto
        LpuDTO lpu,             // O frontend espera este objeto
        List<ItemSolicitacao> itens
) {}