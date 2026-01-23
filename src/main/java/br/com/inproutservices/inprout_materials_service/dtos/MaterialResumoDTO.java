package br.com.inproutservices.inprout_materials_service.dtos;

import br.com.inproutservices.inprout_materials_service.entities.Material;
import java.math.BigDecimal;

public record MaterialResumoDTO(
        Long id,
        String codigo,
        String descricao,
        String modelo,
        String unidadeMedida,
        BigDecimal saldoFisico,
        BigDecimal custoMedioPonderado,
        String empresa
) {
    public MaterialResumoDTO(Material material) {
        this(
                material.getId(),
                material.getCodigo(),
                material.getDescricao(),
                material.getModelo(),
                material.getUnidadeMedida(),
                material.getSaldoFisico(),
                material.getCustoMedioPonderado(),
                material.getEmpresa()
        );
    }
}