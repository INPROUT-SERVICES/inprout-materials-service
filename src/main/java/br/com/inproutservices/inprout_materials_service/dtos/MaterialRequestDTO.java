package br.com.inproutservices.inprout_materials_service.dtos;
import java.math.BigDecimal;
public record MaterialRequestDTO(
        String codigo, String descricao, String modelo, String numeroDeSerie,
        String unidadeMedida, BigDecimal saldoFisicoInicial, BigDecimal custoUnitarioInicial,
        String observacoes, String empresa
) {}