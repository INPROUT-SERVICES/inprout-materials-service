package br.com.inproutservices.inprout_materials_service.services.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DecisaoItemDTO(

        @NotBlank(message = "A ação é obrigatória.")
        @Pattern(regexp = "^(APROVAR|REJEITAR)$", message = "A ação deve ser 'APROVAR' ou 'REJEITAR'.")
        String acao,

        String observacao,

        Long aprovadorId // Opcional, dependendo de como você pega o usuário logado (via token ou body)
) {}