package br.com.inproutservices.inprout_materials_service.entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "itens_solicitacao")
public class ItemSolicitacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "solicitacao_id", nullable = false)
    @JsonIgnore
    private Solicitacao solicitacao;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @Column(name = "quantidade_solicitada", nullable = false, precision = 10, scale = 2)
    private BigDecimal quantidadeSolicitada;

    public ItemSolicitacao() {}

    public ItemSolicitacao(Solicitacao solicitacao, Material material, BigDecimal quantidadeSolicitada) {
        this.solicitacao = solicitacao;
        this.material = material;
        this.quantidadeSolicitada = quantidadeSolicitada;
    }

    // Getters e Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Solicitacao getSolicitacao() { return solicitacao; }
    public void setSolicitacao(Solicitacao solicitacao) { this.solicitacao = solicitacao; }
    public Material getMaterial() { return material; }
    public void setMaterial(Material material) { this.material = material; }
    public BigDecimal getQuantidadeSolicitada() { return quantidadeSolicitada; }
    public void setQuantidadeSolicitada(BigDecimal quantidadeSolicitada) { this.quantidadeSolicitada = quantidadeSolicitada; }
}