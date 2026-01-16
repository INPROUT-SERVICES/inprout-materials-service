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

    public List<SolicitacaoResponseDTO> listarPendentes(String userRole) {
        return solicitacaoRepository.findAll().stream()
                .filter(s -> ehPendenteParaRole(s, userRole))
                .map(this::converterParaDTO)
                .collect(Collectors.toList());
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
        if (r.equals("COORDINATOR")) {
            return s.getStatus() == StatusSolicitacao.PENDENTE_COORDENADOR;
        }
        if (r.equals("CONTROLLER") || r.equals("ADMIN")) {
            return s.getStatus() == StatusSolicitacao.PENDENTE_CONTROLLER;
        }
        return false;
    }

    @Transactional
    public void processarLote(DecisaoLoteDTO dto, String acao, String role) {
        List<Solicitacao> solicitacoes = solicitacaoRepository.findAllById(dto.ids());
        String roleUpper = role.toUpperCase();

        for (Solicitacao s : solicitacoes) {
            if ("APROVAR".equals(acao)) {
                s.getItens().forEach(item -> {
                    if (item.getStatusItem() == StatusItem.PENDENTE) {
                        item.setStatusItem(StatusItem.APROVADO);
                    }
                });
                avancarFase(s, roleUpper);
            } else if ("REJEITAR".equals(acao)) {
                rejeitarSolicitacao(s, dto.observacao());
            }
        }
        solicitacaoRepository.saveAll(solicitacoes);
    }

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

        Solicitacao s = item.getSolicitacao();
        if (s.getItens().stream().allMatch(i -> i.getStatusItem() != StatusItem.PENDENTE)) {
            avancarFase(s, roleUpper);
        }
    }

    private void avancarFase(Solicitacao s, String role) {
        boolean temAprovados = s.getItens().stream().anyMatch(i -> i.getStatusItem() == StatusItem.APROVADO);

        if (!temAprovados) {
            s.setStatus(StatusSolicitacao.REPROVADO);
            return;
        }

        if (role.equals("COORDINATOR")) {
            s.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
            s.getItens().stream()
                    .filter(i -> i.getStatusItem() == StatusItem.APROVADO)
                    .forEach(i -> i.setStatusItem(StatusItem.PENDENTE));
        } else if (role.equals("CONTROLLER") || role.equals("ADMIN")) {
            aprovarFinal(s);
        }
    }

    private void aprovarFinal(Solicitacao s) {
        s.setStatus(StatusSolicitacao.APROVADO);
        BigDecimal custoTotal = BigDecimal.ZERO;

        for (ItemSolicitacao item : s.getItens()) {
            if (item.getStatusItem() == StatusItem.APROVADO) {
                // AJUSTE: Proteção contra custo nulo para evitar erro no cálculo
                BigDecimal preco = item.getMaterial().getCustoMedioPonderado();
                if (preco == null) preco = BigDecimal.ZERO;

                BigDecimal custoItem = preco.multiply(item.getQuantidadeSolicitada());
                custoTotal = custoTotal.add(custoItem);
            }
        }

        if (s.getOsId() != null && custoTotal.compareTo(BigDecimal.ZERO) > 0) {
            atualizarFinanceiroMonolito(s.getOsId(), custoTotal);
        }
    }

    private void rejeitarSolicitacao(Solicitacao s, String motivo) {
        s.setStatus(StatusSolicitacao.REPROVADO);
        for (ItemSolicitacao item : s.getItens()) {
            if (item.getStatusItem() != StatusItem.REPROVADO) {
                Material m = item.getMaterial();
                m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
                materialRepository.save(m);
                item.setStatusItem(StatusItem.REPROVADO);
                item.setMotivoRecusa(motivo);
            }
        }
    }

    private void atualizarFinanceiroMonolito(Long osId, BigDecimal valor) {
        try {
            // CORREÇÃO: Removido o "/api" pois o OsController no monolito mapeia diretamente para "/os"
            String url = monolithUrl + "/os/" + osId + "/adicionar-custo-material";
            restTemplate.postForLocation(url, valor);
        } catch (Exception e) {
            System.err.println("ERRO INTEGRACAO OS " + osId + ": " + e.getMessage());
        }
    }

    private SolicitacaoResponseDTO converterParaDTO(Solicitacao s) {
        return new SolicitacaoResponseDTO(
                s.getId(), s.getDataSolicitacao(), s.getJustificativa(), s.getStatus(),
                montarNomeSolicitante(s.getSolicitanteId()), montarOsDTO(s.getOsId()),
                montarLpuDTO(s.getLpuId()), s.getItens()
        );
    }

    @SuppressWarnings("unchecked")
    private OsDTO montarOsDTO(Long osId) {
        SegmentoDTO segmentoFallback = new SegmentoDTO(0L, "-");
        String osNumeroFallback = (osId == null) ? "N/A" : String.valueOf(osId);
        OsDTO fallback = new OsDTO(osId, osNumeroFallback, segmentoFallback);

        if (osId == null) return fallback;

        for (String base : monolithBaseUrls()) {
            // AJUSTE: Tentamos com e sem /api para garantir compatibilidade
            String[] paths = {"/os/" + osId, "/api/os/" + osId};
            for (String path : paths) {
                Map<String, Object> map = tryGetMap(base + path);
                if (map == null || map.isEmpty()) continue;

                String osNumero = firstNonBlank(
                        asString(map.get("os")), asString(map.get("numeroOS")),
                        asString(map.get("numero")), asString(map.get("codigo"))
                );

                SegmentoDTO seg = segmentoFallback;
                if (map.get("segmento") instanceof Map segMap) {
                    seg = new SegmentoDTO(asLong(segMap.get("id")), firstNonBlank(asString(segMap.get("nome")), "-"));
                }
                return new OsDTO(asLong(map.get("id")), osNumero != null ? osNumero : osNumeroFallback, seg);
            }
        }
        return fallback;
    }

    private LpuDTO montarLpuDTO(Long lpuId) {
        if (lpuId == null || lpuId == 0) return new LpuDTO(0L, "Contrato não informado", "-");
        for (String base : monolithBaseUrls()) {
            Map<String, Object> map = tryGetMap(base + "/os/detalhes/" + lpuId);
            if (map != null && map.get("objetoContratado") != null) {
                return new LpuDTO(lpuId, asString(map.get("objetoContratado")), "-");
            }
        }
        return new LpuDTO(lpuId, "Objeto não encontrado", "-");
    }

    private String montarNomeSolicitante(Long id) {
        if (id == null) return "Não informado";
        for (String base : monolithBaseUrls()) {
            Map<String, Object> map = tryGetMap(base + "/usuarios/" + id);
            if (map != null && map.get("nome") != null) return asString(map.get("nome"));
        }
        return "Solicitante #" + id;
    }

    private Map<String, Object> tryGetMap(String url) {
        try { return restTemplate.getForObject(url, Map.class); } catch (Exception e) { return null; }
    }

    private List<String> monolithBaseUrls() {
        return List.of(monolithUrl, "http://localhost:8080");
    }

    private String firstNonBlank(String... v) { for (String s : v) if (s != null && !s.isBlank()) return s; return null; }
    private String asString(Object o) { return o == null ? null : String.valueOf(o).trim(); }
    private Long asLong(Object o) { try { return Long.valueOf(asString(o)); } catch (Exception e) { return null; } }
}