package br.com.inproutservices.inprout_materials_service.entities;

import br.com.inproutservices.inprout_materials_service.enums.StatusSolicitacao;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "solicitacoes")
public class Solicitacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // IMPORTANT: Use EnumType.STRING to avoid issues with order changes
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusSolicitacao status;

    @Column(name = "data_solicitacao", nullable = false)
    private LocalDateTime dataSolicitacao;

    @Column(name = "justificativa")
    private String justificativa;

    @Column(name = "observacao_reprovacao")
    private String observacaoReprovacao;

    @Column(name = "os_id")
    private Long osId;

    @Column(name = "lpu_id")
    private Long lpuId;

    @Column(name = "solicitante_id")
    private Long solicitanteId;

    @Column(name = "aprovador_id")
    private Long aprovadorId;

    @Column(name = "segmento_id")
    private Long segmentoId;

    @Column(name = "site")
    private String site;

    @OneToMany(mappedBy = "solicitacao", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<ItemSolicitacao> itens = new ArrayList<>();

    // --- 1. MANDATORY NO-ARG CONSTRUCTOR FOR HIBERNATE ---
    public Solicitacao() {
    }

    // Getters and Setters...

    public String getSite() {
        return site;
    }

    public void setSite(String site) {
        this.site = site;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public StatusSolicitacao getStatus() { return status; }
    public void setStatus(StatusSolicitacao status) { this.status = status; }
    public LocalDateTime getDataSolicitacao() { return dataSolicitacao; }
    public void setDataSolicitacao(LocalDateTime dataSolicitacao) { this.dataSolicitacao = dataSolicitacao; }
    public String getJustificativa() { return justificativa; }
    public void setJustificativa(String justificativa) { this.justificativa = justificativa; }
    public String getObservacaoReprovacao() { return observacaoReprovacao; }
    public void setObservacaoReprovacao(String observacaoReprovacao) { this.observacaoReprovacao = observacaoReprovacao; }
    public Long getOsId() { return osId; }
    public void setOsId(Long osId) { this.osId = osId; }
    public Long getLpuId() { return lpuId; }
    public void setLpuId(Long lpuId) { this.lpuId = lpuId; }
    public Long getSolicitanteId() { return solicitanteId; }
    public void setSolicitanteId(Long solicitanteId) { this.solicitanteId = solicitanteId; }
    public Long getAprovadorId() { return aprovadorId; }
    public void setAprovadorId(Long aprovadorId) { this.aprovadorId = aprovadorId; }
    public List<ItemSolicitacao> getItens() { return itens; }
    public void setItens(List<ItemSolicitacao> itens) { this.itens = itens; }

    public Long getSegmentoId() {
        return segmentoId;
    }

    public void setSegmentoId(Long segmentoId) {
        this.segmentoId = segmentoId;
    }
}