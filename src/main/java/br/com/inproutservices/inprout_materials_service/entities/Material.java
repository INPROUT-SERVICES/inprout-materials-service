package br.com.inproutservices.inprout_materials_service.entities;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "materiais")
public class Material {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String codigo;

    @Column(nullable = false, length = 255)
    private String descricao;

    @Column(length = 100)
    private String modelo;

    @Column(name = "numero_serie", length = 100)
    private String numeroDeSerie;

    @Column(name = "unidade_medida", nullable = false, length = 10)
    private String unidadeMedida;

    @Column(name = "saldo_fisico", nullable = false, precision = 10, scale = 2)
    private BigDecimal saldoFisico;

    @Column(name = "custo_medio_ponderado", precision = 10, scale = 4)
    private BigDecimal custoMedioPonderado;

    @Column(name = "data_atualizacao")
    private LocalDateTime dataAtualizacao;

    public Material() {}

    @PrePersist
    @PreUpdate
    public void prePersistOrUpdate() {
        dataAtualizacao = LocalDateTime.now();
    }

    // --- Getters e Setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public String getModelo() { return modelo; }
    public void setModelo(String modelo) { this.modelo = modelo; }
    public String getNumeroDeSerie() { return numeroDeSerie; }
    public void setNumeroDeSerie(String numeroDeSerie) { this.numeroDeSerie = numeroDeSerie; }
    public String getUnidadeMedida() { return unidadeMedida; }
    public void setUnidadeMedida(String unidadeMedida) { this.unidadeMedida = unidadeMedida; }
    public BigDecimal getSaldoFisico() { return saldoFisico; }
    public void setSaldoFisico(BigDecimal saldoFisico) { this.saldoFisico = saldoFisico; }
    public BigDecimal getCustoMedioPonderado() { return custoMedioPonderado; }
    public void setCustoMedioPonderado(BigDecimal custoMedioPonderado) { this.custoMedioPonderado = custoMedioPonderado; }
    public LocalDateTime getDataAtualizacao() { return dataAtualizacao; }
    public void setDataAtualizacao(LocalDateTime dataAtualizacao) { this.dataAtualizacao = dataAtualizacao; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Material material = (Material) o;
        return Objects.equals(id, material.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}