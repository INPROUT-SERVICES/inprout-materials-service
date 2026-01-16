package br.com.inproutservices.inprout_materials_service.services;

import br.com.inproutservices.inprout_materials_service.dtos.CriarSolicitacaoLoteDTO;
import br.com.inproutservices.inprout_materials_service.dtos.DecisaoLoteDTO;
import br.com.inproutservices.inprout_materials_service.dtos.response.*;
import br.com.inproutservices.inprout_materials_service.entities.ItemSolicitacao;
import br.com.inproutservices.inprout_materials_service.entities.Material;
import br.com.inproutservices.inprout_materials_service.entities.Solicitacao;
import br.com.inproutservices.inprout_materials_service.enums.StatusItem;
import br.com.inproutservices.inprout_materials_service.enums.StatusSolicitacao;
import br.com.inproutservices.inprout_materials_service.repositories.ItemSolicitacaoRepository;
import br.com.inproutservices.inprout_materials_service.repositories.MaterialRepository;
import br.com.inproutservices.inprout_materials_service.repositories.SolicitacaoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SolicitacaoService {

    private final SolicitacaoRepository solicitacaoRepository;
    private final MaterialRepository materialRepository;
    private final ItemSolicitacaoRepository itemSolicitacaoRepository;
    private final RestTemplate restTemplate;

    @Value("${app.monolith.url:http://localhost:8080}")
    private String monolithUrl;

    public SolicitacaoService(SolicitacaoRepository solicitacaoRepository,
                              MaterialRepository materialRepository,
                              ItemSolicitacaoRepository itemSolicitacaoRepository,
                              RestTemplate restTemplate) {
        this.solicitacaoRepository = solicitacaoRepository;
        this.materialRepository = materialRepository;
        this.itemSolicitacaoRepository = itemSolicitacaoRepository;
        this.restTemplate = restTemplate;
    }

    // --- CRIAÇÃO ---
    @Transactional
    public List<Solicitacao> criarSolicitacaoEmLote(CriarSolicitacaoLoteDTO dto) {
        Solicitacao solicitacao = new Solicitacao();
        solicitacao.setOsId(dto.osId());
        solicitacao.setLpuId(dto.lpuItemId() != null ? dto.lpuItemId() : 0L);
        solicitacao.setSolicitanteId(dto.solicitanteId());
        solicitacao.setJustificativa(dto.observacoes());
        solicitacao.setStatus(StatusSolicitacao.PENDENTE_COORDENADOR);
        solicitacao.setDataSolicitacao(LocalDateTime.now());

        if (solicitacao.getItens() == null) {
            solicitacao.setItens(new ArrayList<>());
        }

        for (CriarSolicitacaoLoteDTO.ItemLoteRequest itemDto : dto.itens()) {
            Material material = materialRepository.findById(itemDto.materialId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Material não encontrado"));

            // Abate do estoque provisoriamente
            material.setSaldoFisico(material.getSaldoFisico().subtract(itemDto.quantidade()));
            materialRepository.save(material);

            ItemSolicitacao item = new ItemSolicitacao(solicitacao, material, itemDto.quantidade());
            item.setStatusItem(StatusItem.PENDENTE);
            solicitacao.getItens().add(item);
        }

        Solicitacao salva = solicitacaoRepository.save(solicitacao);
        return List.of(salva);
    }

    // --- LISTAGEM PENDENTES ---
    public List<SolicitacaoResponseDTO> listarPendentes(String userRole) {
        return solicitacaoRepository.findAll().stream()
                .filter(s -> ehPendenteParaRole(s, userRole))
                .map(this::converterParaDTO)
                .collect(Collectors.toList());
    }

    // --- LISTAGEM HISTÓRICO ---
    public List<SolicitacaoResponseDTO> listarHistorico(String userRole, Long userId) {
        // Implementar filtros reais de data/usuário se necessário
        return solicitacaoRepository.findAll().stream()
                .filter(s -> !ehPendenteParaRole(s, userRole)) // Tudo que NÃO é pendente é histórico
                .sorted((a, b) -> b.getDataSolicitacao().compareTo(a.getDataSolicitacao()))
                .limit(50) // Limita para não pesar
                .map(this::converterParaDTO)
                .collect(Collectors.toList());
    }

    private boolean ehPendenteParaRole(Solicitacao s, String role) {
        if (role == null) return false;
        String r = role.toUpperCase();

        if (r.equals("COORDINATOR") || r.equals("MANAGER")) {
            return s.getStatus() == StatusSolicitacao.PENDENTE_COORDENADOR;
        }
        if (r.equals("CONTROLLER") || r.equals("ADMIN")) {
            return s.getStatus() == StatusSolicitacao.PENDENTE_CONTROLLER;
        }
        return false;
    }

    // --- DECISÃO EM LOTE ---
    @Transactional
    public void processarLote(DecisaoLoteDTO dto, String acao, String role) {
        List<Solicitacao> solicitacoes = solicitacaoRepository.findAllById(dto.ids());
        String roleUpper = role.toUpperCase();

        for (Solicitacao s : solicitacoes) {
            if ("APROVAR".equals(acao)) {
                // Aprova itens pendentes
                s.getItens().forEach(item -> {
                    if (item.getStatusItem() == StatusItem.PENDENTE) {
                        item.setStatusItem(StatusItem.APROVADO);
                    }
                });
                avancarFase(s, roleUpper);
            } else if ("REJEITAR".equals(acao)) {
                rejeitarSolicitacaoInteira(s, dto.observacao());
            }
        }
        solicitacaoRepository.saveAll(solicitacoes);
    }

    // --- DECISÃO ITEM INDIVIDUAL ---
    @Transactional
    public void decidirItem(Long itemId, String acao, String observacao, String role) {
        ItemSolicitacao item = itemSolicitacaoRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item não encontrado"));

        if ("REJEITAR".equals(acao)) {
            if (observacao == null || observacao.isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Motivo obrigatório.");

            item.setStatusItem(StatusItem.REPROVADO);
            item.setMotivoRecusa(observacao);

            // Devolve ao estoque imediatamente
            Material m = item.getMaterial();
            m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
            materialRepository.save(m);

        } else {
            item.setStatusItem(StatusItem.APROVADO);
            item.setMotivoRecusa(null);
        }
        itemSolicitacaoRepository.save(item);

        // Verifica se pode avançar a solicitação pai
        verificarProgressaoSolicitacao(item.getSolicitacao(), role);
    }

    private void verificarProgressaoSolicitacao(Solicitacao s, String role) {
        boolean todosItensResolvidos = s.getItens().stream()
                .noneMatch(i -> i.getStatusItem() == StatusItem.PENDENTE);

        if (todosItensResolvidos) {
            avancarFase(s, role);
        }
    }

    private void avancarFase(Solicitacao s, String role) {
        boolean temAprovados = s.getItens().stream().anyMatch(i -> i.getStatusItem() == StatusItem.APROVADO);

        if (!temAprovados) {
            // Se tudo foi recusado, a solicitação morre aqui
            s.setStatus(role.equals("CONTROLLER") ? StatusSolicitacao.REJEITADO_CONTROLLER : StatusSolicitacao.REJEITADO_COORDENADOR);
            return;
        }

        if (role.equals("COORDINATOR") || role.equals("MANAGER")) {
            // Vai para o Controller
            s.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER);

            // Resetar itens APROVADOS para PENDENTE para o Controller avaliar novamente?
            // Regra de negócio: O Controller deve revalidar o que o Coordenador aprovou.
            s.getItens().stream()
                    .filter(i -> i.getStatusItem() == StatusItem.APROVADO)
                    .forEach(i -> i.setStatusItem(StatusItem.PENDENTE));

        } else if (role.equals("CONTROLLER") || role.equals("ADMIN")) {
            // Finaliza e Integra
            s.setStatus(StatusSolicitacao.APROVADO); // ou FINALIZADO
            integrarComMonolito(s);
        }
    }

    private void rejeitarSolicitacaoInteira(Solicitacao s, String motivo) {
        s.setStatus(StatusSolicitacao.REPROVADO);
        for (ItemSolicitacao item : s.getItens()) {
            if (item.getStatusItem() != StatusItem.REPROVADO) {
                item.setStatusItem(StatusItem.REPROVADO);
                item.setMotivoRecusa(motivo);
                // Devolve estoque
                Material m = item.getMaterial();
                m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
                materialRepository.save(m);
            }
        }
    }

    private void integrarComMonolito(Solicitacao s) {
        BigDecimal custoTotal = BigDecimal.ZERO;
        List<String> descricoes = new ArrayList<>();

        for (ItemSolicitacao item : s.getItens()) {
            if (item.getStatusItem() == StatusItem.APROVADO) {
                BigDecimal custo = item.getMaterial().getCustoMedioPonderado().multiply(item.getQuantidadeSolicitada());
                custoTotal = custoTotal.add(custo);
                descricoes.add(item.getQuantidadeSolicitada() + "x " + item.getMaterial().getDescricao());
            }
        }

        if (s.getOsId() != null && custoTotal.compareTo(BigDecimal.ZERO) > 0) {
            try {
                // 1. Atualiza Valor na OS
                String urlValor = monolithUrl + "/api/os/" + s.getOsId() + "/adicionar-custo-material";
                restTemplate.postForLocation(urlValor, custoTotal);

                // 2. Opcional: Enviar histórico/comentário para a OS (se o endpoint existir no monolito)
                // String resumo = "Materiais Aprovados (ID " + s.getId() + "): " + String.join(", ", descricoes);
                // restTemplate.postForLocation(monolithUrl + "/api/os/" + s.getOsId() + "/comentario-sistema", resumo);

            } catch (Exception e) {
                System.err.println("Erro ao integrar com Monolito: " + e.getMessage());
                // Não falha a transação, mas loga o erro. Pode-se implementar retry.
            }
        }
    }

    // --- CONVERSORES DTO (Mantidos simplificados) ---
    private SolicitacaoResponseDTO converterParaDTO(Solicitacao s) {
        return new SolicitacaoResponseDTO(
                s.getId(),
                s.getDataSolicitacao(),
                s.getJustificativa(),
                s.getStatus(),
                "Solicitante " + s.getSolicitanteId(), // Simplificado para exemplo
                new OsDTO(s.getOsId(), (s.getOsId() != null ? s.getOsId().toString() : "N/A"), new SegmentoDTO(0L, "-")),
                new LpuDTO(s.getLpuId(), "Item Contrato", "-"),
                s.getItens()
        );
    }
}