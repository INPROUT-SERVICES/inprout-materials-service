package br.com.inproutservices.inprout_materials_service.dtos;

import java.math.BigDecimal;
import java.util.List;

public record CriarSolicitacaoLoteDTO(
        Long osId,           // Movido para o nível principal (conforme cms.js)
        Long lpuItemId,      // Movido para o nível principal
        Long solicitanteId,  // Renomeado para bater com o padrão ou manter idSolicitante se o JSON mapear assim (cms.js envia 'solicitanteId')
        String observacoes,  // cms.js envia 'observacoes' e não 'justificativa'
        List<ItemLoteRequest> itens
) {
    public record ItemLoteRequest(
            Long materialId,
            BigDecimal quantidade
    ) {}
}