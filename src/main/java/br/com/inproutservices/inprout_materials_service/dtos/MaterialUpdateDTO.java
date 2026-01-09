package br.com.inproutservices.inprout_materials_service.dtos;
import java.math.BigDecimal;
public record MaterialUpdateDTO(
        String codigo, String descricao, String modelo, String numeroDeSerie,
        String observacoes, BigDecimal saldoFisico
) {}