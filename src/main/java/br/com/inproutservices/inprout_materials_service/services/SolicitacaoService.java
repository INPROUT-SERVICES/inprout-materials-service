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

    @Value("${app.monolith.url:http://inprout-monolito-homolog:8080}")
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

        if (solicitacao.getItens() == null) solicitacao.setItens(new ArrayList<>());

        for (CriarSolicitacaoLoteDTO.ItemLoteRequest itemDto : dto.itens()) {
            Material material = materialRepository.findById(itemDto.materialId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Material não encontrado"));
            material.setSaldoFisico(material.getSaldoFisico().subtract(itemDto.quantidade()));
            materialRepository.save(material);

            ItemSolicitacao item = new ItemSolicitacao(solicitacao, material, itemDto.quantidade());
            item.setStatusItem(StatusItem.PENDENTE);
            solicitacao.getItens().add(item);
        }
        return List.of(solicitacaoRepository.save(solicitacao));
    }

    public List<SolicitacaoResponseDTO> listarPendentes(String userRole, Long userId) {
        if (userRole == null) return new ArrayList<>();

        // --- LOG DE DEBUG ---
        // System.out.println("Listando pendentes para: " + userRole);

        List<Solicitacao> todas = solicitacaoRepository.findAll();

        if ("CONTROLLER".equalsIgnoreCase(userRole) || "ADMIN".equalsIgnoreCase(userRole)) {
            return todas.stream()
                    .filter(s -> s.getStatus() == StatusSolicitacao.PENDENTE_CONTROLLER)
                    .map(this::converterParaDTO)
                    .collect(Collectors.toList());
        }

        if ("COORDINATOR".equalsIgnoreCase(userRole)) {
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
        if ("COORDINATOR".equalsIgnoreCase(role)) return s.getStatus() == StatusSolicitacao.PENDENTE_COORDENADOR;
        if ("CONTROLLER".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role)) return s.getStatus() == StatusSolicitacao.PENDENTE_CONTROLLER;
        return false;
    }

    @Transactional
    public void processarLote(DecisaoLoteDTO dto, String acao, String role) {
        System.out.println(">>> PROCESSAR LOTE INICIADO. Ação: " + acao + " | Role: " + role);

        List<ItemSolicitacao> itens = itemSolicitacaoRepository.findAllById(dto.ids());
        Set<Solicitacao> solicitacoesAfetadas = new HashSet<>();

        for (ItemSolicitacao item : itens) {
            if ("APROVAR".equalsIgnoreCase(acao)) {
                if (item.getStatusItem() == StatusItem.PENDENTE) {
                    item.setStatusItem(StatusItem.APROVADO);
                    solicitacoesAfetadas.add(item.getSolicitacao());
                }
            } else if ("REJEITAR".equalsIgnoreCase(acao)) {
                item.setStatusItem(StatusItem.REPROVADO);
                item.setMotivoRecusa(dto.observacao());
                Material m = item.getMaterial();
                m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
                materialRepository.save(m);
                solicitacoesAfetadas.add(item.getSolicitacao());
            }
        }
        itemSolicitacaoRepository.saveAll(itens);

        for (Solicitacao s : solicitacoesAfetadas) {
            avancarFase(s, role);
        }
    }

    @Transactional
    public void decidirItem(Long itemId, String acao, String observacao, String role) {
        System.out.println(">>> DECIDIR ITEM INICIADO. ID: " + itemId + " | Ação: " + acao + " | Role: " + role);

        ItemSolicitacao item = itemSolicitacaoRepository.findById(itemId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if ("REJEITAR".equalsIgnoreCase(acao)) {
            item.setStatusItem(StatusItem.REPROVADO);
            item.setMotivoRecusa(observacao);
            Material m = item.getMaterial();
            m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
            materialRepository.save(m);
        } else {
            item.setStatusItem(StatusItem.APROVADO);
        }
        itemSolicitacaoRepository.save(item);
        avancarFase(item.getSolicitacao(), role);
    }

    private void avancarFase(Solicitacao s, String role) {
        System.out.println(">>> VERIFICANDO AVANÇO DE FASE. Status Atual: " + s.getStatus());

        // 1. Verifica se tem algum pendente
        boolean existePendente = s.getItens().stream().anyMatch(i -> i.getStatusItem() == StatusItem.PENDENTE);
        if (existePendente) {
            System.out.println(">>> AINDA HÁ ITENS PENDENTES. NÃO AVANÇA.");
            solicitacaoRepository.save(s);
            return;
        }

        // 2. Se tudo foi rejeitado
        boolean todosRejeitados = s.getItens().stream().allMatch(i -> i.getStatusItem() == StatusItem.REPROVADO);
        if (todosRejeitados) {
            s.setStatus(StatusSolicitacao.REPROVADO);
            solicitacaoRepository.save(s);
            return;
        }

        // 3. Logica de papéis (AGORA USANDO IGNORE CASE)
        if ("COORDINATOR".equalsIgnoreCase(role)) {
            System.out.println(">>> COORDENADOR APROVOU. AVANÇANDO PARA CONTROLLER.");
            s.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
            s.getItens().stream()
                    .filter(i -> i.getStatusItem() == StatusItem.APROVADO)
                    .forEach(i -> i.setStatusItem(StatusItem.PENDENTE));

        } else if ("CONTROLLER".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role)) {
            System.out.println(">>> CONTROLLER APROVOU FINAL. CHAMANDO INTEGRAÇÃO...");
            aprovarFinal(s);
        } else {
            System.out.println(">>> ROLE NÃO RECONHECIDA PARA AVANÇO: " + role);
        }

        solicitacaoRepository.save(s);
    }

    private void aprovarFinal(Solicitacao s) {
        s.setStatus(StatusSolicitacao.APROVADO);
        BigDecimal custoTotal = BigDecimal.ZERO;

        for (ItemSolicitacao item : s.getItens()) {
            if (item.getStatusItem() == StatusItem.APROVADO) {
                BigDecimal preco = item.getMaterial().getCustoMedioPonderado() != null ? item.getMaterial().getCustoMedioPonderado() : BigDecimal.ZERO;
                BigDecimal qtd = item.getQuantidadeSolicitada() != null ? item.getQuantidadeSolicitada() : BigDecimal.ZERO;
                custoTotal = custoTotal.add(preco.multiply(qtd));
            }
        }

        System.out.println(">>> CUSTO CALCULADO: R$ " + custoTotal);
        if (s.getOsId() != null) {
            atualizarFinanceiroMonolito(s.getOsId(), custoTotal);
        }
    }

    private void atualizarFinanceiroMonolito(Long osId, BigDecimal valor) {
        List<String> urlsToTry = new ArrayList<>();
        urlsToTry.add("http://inprout-monolito-homolog:8080/os/" + osId + "/adicionar-custo-material");
        urlsToTry.add(monolithUrl + "/os/" + osId + "/adicionar-custo-material");

        boolean sucesso = false;

        for (String url : urlsToTry) {
            if (sucesso) break;
            try {
                System.out.println(">>> TENTANDO POST EM: " + url);
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                org.springframework.http.HttpEntity<BigDecimal> request = new org.springframework.http.HttpEntity<>(valor, headers);

                restTemplate.postForLocation(url, request);
                System.out.println(">>> SUCESSO! VALOR INTEGRADO.");
                sucesso = true;
            } catch (Exception e) {
                System.out.println(">>> FALHA EM " + url + ": " + e.getMessage());
            }
        }
    }

    // --- MÉTODOS DE DTO (Mantidos iguais) ---
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
        if (segmentoUsuario == null) return true;
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
        String[] baseUrls = {"http://inprout-monolito-homolog:8080", monolithUrl};
        for (String base : baseUrls) {
            String url = base + "/os/" + osId;
            Map<String, Object> map = tryGetMap(url);
            if (map != null && !map.isEmpty()) {
                String num = firstNonBlank(asString(map.get("os")), asString(map.get("numeroOS")), String.valueOf(osId));
                SegmentoDTO seg = fallbackSeg;
                if (map.get("segmento") instanceof Map segMap) {
                    seg = new SegmentoDTO(asLong(segMap.get("id")), firstNonBlank(asString(segMap.get("nome")), "-"));
                }
                return new OsDTO(asLong(map.get("id")), num, seg);
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