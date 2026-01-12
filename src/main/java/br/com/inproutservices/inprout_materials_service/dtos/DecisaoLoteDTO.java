package br.com.inproutservices.inprout_materials_service.dtos;

import java.util.List;

public record DecisaoLoteDTO(
        List<Long> ids,
        Long aprovadorId,
        String observacao
) {}