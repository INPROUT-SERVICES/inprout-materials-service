package br.com.inproutservices.inprout_materials_service.dtos;

import java.math.BigDecimal;
import java.util.List;

public record SolicitacaoLoteRequestDTO(
        Long osId,
        Long lpuItemId,
        String observacoes,
        List<ItemLoteDTO> itens
) {
    public record ItemLoteDTO(
            Long materialId,
            BigDecimal quantidade
    ) {}
}