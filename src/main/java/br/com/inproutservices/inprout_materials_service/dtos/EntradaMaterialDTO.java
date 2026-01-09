package br.com.inproutservices.inprout_materials_service.dtos;
import java.math.BigDecimal;
public record EntradaMaterialDTO(
        Long materialId, BigDecimal quantidade, BigDecimal custoUnitario, String observacoes
) {}