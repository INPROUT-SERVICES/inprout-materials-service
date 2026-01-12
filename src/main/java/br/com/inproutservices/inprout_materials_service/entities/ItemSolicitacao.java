package br.com.inproutservices.inprout_materials_service.entities;

import br.com.inproutservices.inprout_materials_service.enums.StatusItem;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status_item", nullable = false)
    private StatusItem statusItem = StatusItem.PENDENTE;

    @Column(name = "motivo_recusa")
    private String motivoRecusa;

    public ItemSolicitacao() {}

    public ItemSolicitacao(Solicitacao solicitacao, Material material, BigDecimal quantidadeSolicitada) {
        this.solicitacao = solicitacao;
        this.material = material;
        this.quantidadeSolicitada = quantidadeSolicitada;
        this.statusItem = StatusItem.PENDENTE;
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
    public StatusItem getStatusItem() { return statusItem; }
    public void setStatusItem(StatusItem statusItem) { this.statusItem = statusItem; }
    public String getMotivoRecusa() { return motivoRecusa; }
    public void setMotivoRecusa(String motivoRecusa) { this.motivoRecusa = motivoRecusa; }
}