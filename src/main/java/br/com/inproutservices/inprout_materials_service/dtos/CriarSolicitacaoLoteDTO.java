package br.com.inproutservices.inprout_materials_service.dtos;

import java.math.BigDecimal;
import java.util.List;

public record CriarSolicitacaoLoteDTO(
        Long idSolicitante,
        String justificativa,
        List<ItemLoteRequest> itens
) {
    public record ItemLoteRequest(
            Long osId,
            Long lpuId,
            Long materialId, // O material vem preenchido da linha
            BigDecimal quantidade
    ) {}
}