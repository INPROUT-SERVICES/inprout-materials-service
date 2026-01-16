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
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SolicitacaoService {

    private final SolicitacaoRepository solicitacaoRepository;
    private final MaterialRepository materialRepository;
    private final ItemSolicitacaoRepository itemSolicitacaoRepository;
    private final RestTemplate restTemplate;

    @Value("${app.monolith.url:http://app-backend:8080}")
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
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Material não encontrado: " + itemDto.materialId()));

            material.setSaldoFisico(material.getSaldoFisico().subtract(itemDto.quantidade()));
            materialRepository.save(material);

            ItemSolicitacao item = new ItemSolicitacao(solicitacao, material, itemDto.quantidade());
            item.setStatusItem(StatusItem.PENDENTE);
            solicitacao.getItens().add(item);
        }

        Solicitacao salva = solicitacaoRepository.save(solicitacao);
        return List.of(salva);
    }

    // --- LISTAGEM COM FILTRO DE SEGMENTO E STATUS ---
    public List<SolicitacaoResponseDTO> listarPendentes(String userRole, Long userId) {
        if (userRole == null) return new ArrayList<>();
        String roleUpper = userRole.toUpperCase();
        List<Solicitacao> todas = solicitacaoRepository.findAll();

        // 1. Controller/Admin vê tudo que é da fase dele
        if ("CONTROLLER".equals(roleUpper) || "ADMIN".equals(roleUpper)) {
            return todas.stream()
                    .filter(s -> s.getStatus() == StatusSolicitacao.PENDENTE_CONTROLLER)
                    .map(this::converterParaDTO)
                    .collect(Collectors.toList());
        }

        // 2. Coordenador vê status dele + Filtro de Segmento
        if ("COORDINATOR".equals(roleUpper)) {
            Long segmentoUsuario = buscarSegmentoDoUsuario(userId);
            return todas.stream()
                    .filter(s -> s.getStatus() == StatusSolicitacao.PENDENTE_COORDENADOR)
                    .filter(s -> pertenceAoSegmento(s, segmentoUsuario))
                    .map(this::converterParaDTO)
                    .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }

    public List<SolicitacaoResponseDTO> listarHistorico(String userRole, Long userId) {
        return solicitacaoRepository.findAll().stream()
                .filter(s -> !ehPendenteParaRole(s, userRole))
                .sorted((a, b) -> b.getDataSolicitacao().compareTo(a.getDataSolicitacao()))
                .limit(50)
                .map(this::converterParaDTO)
                .collect(Collectors.toList());
    }

    private boolean ehPendenteParaRole(Solicitacao s, String role) {
        if (role == null) return false;
        String r = role.toUpperCase();
        if (r.equals("COORDINATOR")) return s.getStatus() == StatusSolicitacao.PENDENTE_COORDENADOR;
        if (r.equals("CONTROLLER") || r.equals("ADMIN")) return s.getStatus() == StatusSolicitacao.PENDENTE_CONTROLLER;
        return false;
    }

    // --- PROCESSAMENTO EM LOTE POR ITEM ---
    @Transactional
    public void processarLote(DecisaoLoteDTO dto, String acao, String role) {
        // Busca Itens (e não Solicitações) para garantir ação granular
        List<ItemSolicitacao> itens = itemSolicitacaoRepository.findAllById(dto.ids());
        String roleUpper = role.toUpperCase();
        Set<Solicitacao> solicitacoesAfetadas = new HashSet<>();

        for (ItemSolicitacao item : itens) {
            if ("APROVAR".equals(acao)) {
                if (item.getStatusItem() == StatusItem.PENDENTE) {
                    item.setStatusItem(StatusItem.APROVADO);
                    solicitacoesAfetadas.add(item.getSolicitacao());
                }
            } else if ("REJEITAR".equals(acao)) {
                item.setStatusItem(StatusItem.REPROVADO);
                item.setMotivoRecusa(dto.observacao());

                // Devolve estoque
                Material m = item.getMaterial();
                m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
                materialRepository.save(m);

                solicitacoesAfetadas.add(item.getSolicitacao());
            }
        }
        itemSolicitacaoRepository.saveAll(itens);

        // Verifica progresso de cada solicitação afetada
        for (Solicitacao s : solicitacoesAfetadas) {
            avancarFase(s, roleUpper);
        }
    }

    // --- DECISÃO INDIVIDUAL ---
    @Transactional
    public void decidirItem(Long itemId, String acao, String observacao, String role) {
        ItemSolicitacao item = itemSolicitacaoRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String roleUpper = (role != null) ? role.toUpperCase() : "";

        if ("REJEITAR".equals(acao)) {
            item.setStatusItem(StatusItem.REPROVADO);
            item.setMotivoRecusa(observacao);
            Material m = item.getMaterial();
            m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
            materialRepository.save(m);
        } else {
            item.setStatusItem(StatusItem.APROVADO);
        }
        itemSolicitacaoRepository.save(item);

        avancarFase(item.getSolicitacao(), roleUpper);
    }

    // --- LÓGICA DE TRANSIÇÃO DE FASE (CORRIGIDA) ---
    private void avancarFase(Solicitacao s, String role) {
        // REGRA 1: Se existe algum item PENDENTE, não avança.
        boolean existePendente = s.getItens().stream().anyMatch(i -> i.getStatusItem() == StatusItem.PENDENTE);
        if (existePendente) {
            solicitacaoRepository.save(s);
            return;
        }

        // REGRA 2: Se todos foram REPROVADOS, finaliza como Reprovado.
        boolean todosReprovados = s.getItens().stream().allMatch(i -> i.getStatusItem() == StatusItem.REPROVADO);
        if (todosReprovados) {
            s.setStatus(StatusSolicitacao.REPROVADO);
            solicitacaoRepository.save(s);
            return;
        }

        // REGRA 3: Avança fase
        if (role.equals("COORDINATOR")) {
            s.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
            // Reseta APROVADOS para PENDENTE para o Controller validar
            s.getItens().stream()
                    .filter(i -> i.getStatusItem() == StatusItem.APROVADO)
                    .forEach(i -> i.setStatusItem(StatusItem.PENDENTE));
        } else if (role.equals("CONTROLLER") || role.equals("ADMIN")) {
            aprovarFinal(s);
        }
        solicitacaoRepository.save(s);
    }

    private void aprovarFinal(Solicitacao s) {
        s.setStatus(StatusSolicitacao.APROVADO);
        BigDecimal custoTotal = BigDecimal.ZERO;

        for (ItemSolicitacao item : s.getItens()) {
            if (item.getStatusItem() == StatusItem.APROVADO) {
                BigDecimal preco = item.getMaterial().getCustoMedioPonderado();
                if (preco == null) preco = BigDecimal.ZERO;
                custoTotal = custoTotal.add(preco.multiply(item.getQuantidadeSolicitada()));
            }
        }

        if (s.getOsId() != null && custoTotal.compareTo(BigDecimal.ZERO) > 0) {
            atualizarFinanceiroMonolito(s.getOsId(), custoTotal);
        }
    }

    private void atualizarFinanceiroMonolito(Long osId, BigDecimal valor) {
        try {
            // URL corrigida para o OsController do monolito (/os/)
            String url = monolithUrl + "/os/" + osId + "/adicionar-custo-material";
            restTemplate.postForLocation(url, valor);
        } catch (Exception e) {
            System.err.println("ERRO INTEGRACAO OS " + osId + ": " + e.getMessage());
        }
    }

    // --- MÉTODOS DE SUPORTE E INTEGRAÇÃO ---

    private Long buscarSegmentoDoUsuario(Long userId) {
        if (userId == null) return null;
        try {
            Map<String, Object> map = tryGetMap(monolithUrl + "/usuarios/" + userId);
            if (map != null && map.get("segmento") instanceof Map) {
                Map<String, Object> segMap = (Map<String, Object>) map.get("segmento");
                return asLong(segMap.get("id"));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean pertenceAoSegmento(Solicitacao s, Long segmentoUsuario) {
        if (segmentoUsuario == null) return true; // Se usuário não tem segmento, vê tudo (ou ajuste conforme regra)
        OsDTO os = montarOsDTO(s.getOsId());
        return os.segmento() != null && segmentoUsuario.equals(os.segmento().id());
    }

    private SolicitacaoResponseDTO converterParaDTO(Solicitacao s) {
        return new SolicitacaoResponseDTO(
                s.getId(), s.getDataSolicitacao(), s.getJustificativa(), s.getStatus(),
                montarNomeSolicitante(s.getSolicitanteId()), montarOsDTO(s.getOsId()),
                montarLpuDTO(s.getLpuId()), s.getItens()
        );
    }

    private OsDTO montarOsDTO(Long osId) {
        SegmentoDTO fallbackSeg = new SegmentoDTO(0L, "-");
        if (osId == null) return new OsDTO(null, "N/A", fallbackSeg);

        for (String base : List.of(monolithUrl, "http://localhost:8080")) {
            String[] paths = {"/os/" + osId, "/api/os/" + osId};
            for (String path : paths) {
                Map<String, Object> map = tryGetMap(base + path);
                if (map != null && !map.isEmpty()) {
                    String num = firstNonBlank(asString(map.get("os")), asString(map.get("numeroOS")), String.valueOf(osId));
                    SegmentoDTO seg = fallbackSeg;
                    if (map.get("segmento") instanceof Map segMap) {
                        seg = new SegmentoDTO(asLong(segMap.get("id")), firstNonBlank(asString(segMap.get("nome")), "-"));
                    }
                    return new OsDTO(asLong(map.get("id")), num, seg);
                }
            }
        }
        return new OsDTO(osId, String.valueOf(osId), fallbackSeg);
    }

    private LpuDTO montarLpuDTO(Long lpuId) {
        if (lpuId == null || lpuId == 0) return new LpuDTO(0L, "Contrato não informado", "-");
        Map<String, Object> map = tryGetMap(monolithUrl + "/os/detalhes/" + lpuId);
        return new LpuDTO(lpuId, map != null ? asString(map.get("objetoContratado")) : "Objeto não encontrado", "-");
    }

    private String montarNomeSolicitante(Long id) {
        if (id == null) return "Não informado";
        Map<String, Object> map = tryGetMap(monolithUrl + "/usuarios/" + id);
        return map != null ? asString(map.get("nome")) : "Solicitante #" + id;
    }

    private Map<String, Object> tryGetMap(String url) {
        try { return restTemplate.getForObject(url, Map.class); } catch (Exception e) { return null; }
    }
    private String firstNonBlank(String... v) { for (String s : v) if (s != null && !s.isBlank()) return s; return null; }
    private String asString(Object o) { return o == null ? null : String.valueOf(o).trim(); }
    private Long asLong(Object o) { try { return Long.valueOf(asString(o)); } catch (Exception e) { return null; } }
}