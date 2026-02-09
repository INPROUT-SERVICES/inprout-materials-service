package br.com.inproutservices.inprout_materials_service.entities;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId; // Importação necessária

@Entity
@Table(name = "entradas_material")
public class EntradaMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id", nullable = false)
    @JsonBackReference
    private Material material;

    @Column(nullable = false)
    private BigDecimal quantidade;

    @Column(name = "custo_unitario", nullable = false)
    private BigDecimal custoUnitario;

    @Column(name = "data_entrada", nullable = false)
    private LocalDateTime dataEntrada;

    @Column(columnDefinition = "TEXT")
    private String observacoes;

    @PrePersist
    protected void onCreate() {
        // Garante explicitamente o horário de Brasília
        this.dataEntrada = LocalDateTime.now(ZoneId.of("America/Sao_Paulo"));
    }

    // Getters e Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Material getMaterial() { return material; }
    public void setMaterial(Material material) { this.material = material; }
    public BigDecimal getQuantidade() { return quantidade; }
    public void setQuantidade(BigDecimal quantidade) { this.quantidade = quantidade; }
    public BigDecimal getCustoUnitario() { return custoUnitario; }
    public void setCustoUnitario(BigDecimal custoUnitario) { this.custoUnitario = custoUnitario; }
    public LocalDateTime getDataEntrada() { return dataEntrada; }
    public void setDataEntrada(LocalDateTime dataEntrada) { this.dataEntrada = dataEntrada; }
    public String getObservacoes() { return observacoes; }
    public void setObservacoes(String observacoes) { this.observacoes = observacoes; }
}