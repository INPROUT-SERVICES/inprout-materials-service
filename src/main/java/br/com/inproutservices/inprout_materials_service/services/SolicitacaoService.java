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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SolicitacaoService {

    private final SolicitacaoRepository solicitacaoRepository;
    private final MaterialRepository materialRepository;
    private final ItemSolicitacaoRepository itemSolicitacaoRepository;
    private final RestTemplate restTemplate;

    @Value("${app.monolith.url:http://inprout-monolito:8080}")
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

        Long segmentoId = null;
        if (dto.lpuItemId() != null && dto.lpuItemId() > 0) {
            segmentoId = buscarSegmentoDoItem(dto.lpuItemId());
        }
        if (segmentoId == null) {
            segmentoId = buscarSegmentoDaOs(dto.osId());
        }
        solicitacao.setSegmentoId(segmentoId);

        String site = null;
        if (dto.lpuItemId() != null && dto.lpuItemId() > 0) {
            site = buscarSiteDoItem(dto.lpuItemId());
        }
        if (site == null) {
            site = buscarSiteDaOs(dto.osId());
        }
        solicitacao.setSite(site != null ? site : "-");

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
        String roleUpper = userRole.toUpperCase();

        List<Solicitacao> solicitacoes = new ArrayList<>();

        if (roleUpper.contains("ADMIN")) {
            solicitacoes = solicitacaoRepository.findAllPendentesAdmin();
        }
        else if (roleUpper.contains("CONTROLLER")) {
            solicitacoes = solicitacaoRepository.findByStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
        }
        else if (roleUpper.contains("COORDINATOR") || roleUpper.contains("COORDENADOR") || roleUpper.contains("MANAGER")) {
            List<Long> segmentosUsuario = buscarSegmentosDoUsuario(userId);
            if (segmentosUsuario.isEmpty()) return new ArrayList<>();

            List<Solicitacao> todasPendentes = solicitacaoRepository.findByStatus(StatusSolicitacao.PENDENTE_COORDENADOR);

            // Filtra e corrige usando cache para evitar spam no monólito
            Map<Long, Long> cacheSegmento = new HashMap<>();

            solicitacoes = todasPendentes.stream()
                    .filter(s -> validarOuAtualizarDados(s, segmentosUsuario, cacheSegmento))
                    .collect(Collectors.toList());
        }

        // Prepara caches para a conversão (EVITA N+1 CALLS)
        Map<Long, Map<String, Object>> cacheOs = new HashMap<>();
        Map<Long, Map<String, Object>> cacheLpu = new HashMap<>();
        Map<Long, Map<String, Object>> cacheUser = new HashMap<>();

        return solicitacoes.stream()
                .map(s -> converterParaDTO(s, cacheOs, cacheLpu, cacheUser))
                .collect(Collectors.toList());
    }

    public List<SolicitacaoResponseDTO> listarHistorico(LocalDate inicio, LocalDate fim, String userRole, Long userId) {
        LocalDateTime dataInicio = (inicio != null) ? inicio.atStartOfDay() : LocalDate.now().minusDays(30).atStartOfDay();
        LocalDateTime dataFim = (fim != null) ? fim.atTime(23, 59, 59) : LocalDate.now().atTime(23, 59, 59);

        List<Solicitacao> filtradas = new ArrayList<>();
        String roleUpper = userRole != null ? userRole.toUpperCase() : "";

        if (roleUpper.contains("ADMIN") || roleUpper.contains("CONTROLLER")) {
            filtradas = solicitacaoRepository.findByDataSolicitacaoBetween(dataInicio, dataFim);
        }
        else if (roleUpper.contains("COORDINATOR") || roleUpper.contains("COORDENADOR") || roleUpper.contains("MANAGER")) {
            List<Long> segmentosIds = buscarSegmentosDoUsuario(userId);
            if (!segmentosIds.isEmpty()) {
                filtradas = solicitacaoRepository.findByDataSolicitacaoBetweenAndSegmentoIdIn(
                        dataInicio, dataFim, segmentosIds
                );
            }
        } else {
            filtradas = solicitacaoRepository.findByDataSolicitacaoBetweenAndSolicitanteId(
                    dataInicio, dataFim, userId
            );
        }

        // Corrige dados faltantes (Site/Segmento)
        Map<Long, Long> cacheSegmento = new HashMap<>();
        filtradas.forEach(s -> validarOuAtualizarDados(s, null, cacheSegmento));

        // Caches locais para conversão otimizada
        Map<Long, Map<String, Object>> cacheOs = new HashMap<>();
        Map<Long, Map<String, Object>> cacheLpu = new HashMap<>();
        Map<Long, Map<String, Object>> cacheUser = new HashMap<>();

        return filtradas.stream()
                .sorted((a, b) -> b.getDataSolicitacao().compareTo(a.getDataSolicitacao()))
                .limit(300)
                .map(s -> converterParaDTO(s, cacheOs, cacheLpu, cacheUser))
                .collect(Collectors.toList());
    }

    // --- AUTO-CORREÇÃO OTIMIZADA ---
    private boolean validarOuAtualizarDados(Solicitacao s, List<Long> segmentosUsuario, Map<Long, Long> cacheSegmento) {
        boolean houveAlteracao = false;

        // 1. Correção de Segmento
        if (s.getSegmentoId() == null) {
            Long segmentoRecuperado = null;

            // Tenta cache primeiro (para OS)
            if (cacheSegmento != null && cacheSegmento.containsKey(s.getOsId())) {
                segmentoRecuperado = cacheSegmento.get(s.getOsId());
            } else {
                if (s.getLpuId() != null && s.getLpuId() > 0) {
                    segmentoRecuperado = buscarSegmentoDoItem(s.getLpuId());
                }
                if (segmentoRecuperado == null) {
                    segmentoRecuperado = buscarSegmentoDaOs(s.getOsId());
                }
                // Salva no cache da execução
                if (segmentoRecuperado != null && cacheSegmento != null) {
                    cacheSegmento.put(s.getOsId(), segmentoRecuperado);
                }
            }

            if (segmentoRecuperado != null) {
                s.setSegmentoId(segmentoRecuperado);
                houveAlteracao = true;
            }
        }

        // 2. Correção de Site
        if (s.getSite() == null || s.getSite().isBlank() || "Sem Site".equals(s.getSite()) || "-".equals(s.getSite())) {
            String siteRecuperado = null;
            if (s.getLpuId() != null && s.getLpuId() > 0) {
                siteRecuperado = buscarSiteDoItem(s.getLpuId());
            }
            if (siteRecuperado == null) {
                siteRecuperado = buscarSiteDaOs(s.getOsId());
            }
            if (siteRecuperado != null) {
                s.setSite(siteRecuperado);
                houveAlteracao = true;
            }
        }

        if (houveAlteracao) {
            solicitacaoRepository.save(s);
        }

        // Se segmentosUsuario for null, é porque não precisa filtrar (ex: histórico admin)
        return segmentosUsuario == null || (s.getSegmentoId() != null && segmentosUsuario.contains(s.getSegmentoId()));
    }

    // --- CONVERSORES OTIMIZADOS (COM CACHE) ---

    private SolicitacaoResponseDTO converterParaDTO(Solicitacao s,
                                                    Map<Long, Map<String, Object>> cacheOs,
                                                    Map<Long, Map<String, Object>> cacheLpu,
                                                    Map<Long, Map<String, Object>> cacheUser) {
        return new SolicitacaoResponseDTO(
                s.getId(), s.getDataSolicitacao(), s.getJustificativa(), s.getStatus(),
                montarNomeSolicitante(s.getSolicitanteId(), cacheUser),
                montarOsDTO(s.getOsId(), cacheOs),
                montarLpuDTO(s.getLpuId(), s.getSite(), cacheLpu),
                s.getItens()
        );
    }

    private OsDTO montarOsDTO(Long osId, Map<Long, Map<String, Object>> cache) {
        if (osId == null) return new OsDTO(0L, "-", new SegmentoDTO(0L, "-"));

        Map<String, Object> map;
        if (cache.containsKey(osId)) {
            map = cache.get(osId);
        } else {
            map = buscarNoMonolito("/os/" + osId);
            cache.put(osId, map); // Cache mesmo que seja null
        }

        if (map != null) {
            String num = firstNonBlank(asString(map.get("os")), asString(map.get("numeroOS")), String.valueOf(osId));
            SegmentoDTO seg = new SegmentoDTO(0L, "-");
            if (map.get("segmento") instanceof Map) {
                Map<String, Object> segMap = (Map<String, Object>) map.get("segmento");
                seg = new SegmentoDTO(convertToLong(segMap.get("id")), firstNonBlank(asString(segMap.get("nome")), "-"));
            }
            return new OsDTO(convertToLong(map.get("id")), num, seg);
        }
        return new OsDTO(osId, String.valueOf(osId), new SegmentoDTO(0L, "-"));
    }

    private LpuDTO montarLpuDTO(Long lpuId, String siteSalvo, Map<Long, Map<String, Object>> cache) {
        if (lpuId == null || lpuId == 0) return new LpuDTO(0L, "Não informado", "-", siteSalvo != null ? siteSalvo : "-");

        Map<String, Object> map;
        if (cache.containsKey(lpuId)) {
            map = cache.get(lpuId);
        } else {
            map = buscarNoMonolito("/os/detalhes/" + lpuId);
            cache.put(lpuId, map);
        }

        String codigo = (map != null) ? asString(map.get("objetoContratado")) : "Não encontrado";

        String siteFinal = siteSalvo;
        if (map != null && (siteFinal == null || siteFinal.equals("-") || siteFinal.equals("Sem Site"))) {
            siteFinal = asString(map.get("site"));
        }

        return new LpuDTO(lpuId, codigo, "-", siteFinal != null ? siteFinal : "-");
    }

    private String montarNomeSolicitante(Long id, Map<Long, Map<String, Object>> cache) {
        if (id == null) return "Não informado";

        Map<String, Object> map;
        if (cache.containsKey(id)) {
            map = cache.get(id);
        } else {
            map = buscarNoMonolito("/usuarios/" + id);
            cache.put(id, map);
        }

        return (map != null && map.get("nome") != null) ? asString(map.get("nome")) : "Solicitante #" + id;
    }

    // --- MÉTODOS DE BUSCA E INTEGRAÇÃO ---

    private String buscarSiteDoItem(Long itemId) {
        Map<String, Object> map = buscarNoMonolito("/os/detalhes/" + itemId);
        return map != null ? asString(map.get("site")) : null;
    }

    private String buscarSiteDaOs(Long osId) {
        Map<String, Object> map = buscarNoMonolito("/os/" + osId);
        return map != null ? asString(map.get("site")) : null;
    }

    private List<Long> buscarSegmentosDoUsuario(Long userId) {
        Map<String, Object> map = buscarNoMonolito("/usuarios/" + userId);
        List<Long> ids = new ArrayList<>();
        if (map == null) return ids;
        if (map.containsKey("segmentos") && map.get("segmentos") instanceof List) {
            List<?> lista = (List<?>) map.get("segmentos");
            for (Object item : lista) {
                if (item instanceof Map) {
                    Long id = convertToLong(((Map<?, ?>) item).get("id"));
                    if (id != null) ids.add(id);
                } else if (item instanceof Number) {
                    ids.add(((Number) item).longValue());
                }
            }
        } else if (map.containsKey("segmento") && map.get("segmento") instanceof Map) {
            Map<String, Object> segMap = (Map<String, Object>) map.get("segmento");
            Long id = convertToLong(segMap.get("id"));
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private Long buscarSegmentoDoItem(Long itemId) {
        Map<String, Object> map = buscarNoMonolito("/os/detalhes/" + itemId);
        if (map != null && map.get("segmento") instanceof Map) {
            Map<String, Object> segMap = (Map<String, Object>) map.get("segmento");
            return convertToLong(segMap.get("id"));
        }
        return null;
    }

    private Long buscarSegmentoDaOs(Long osId) {
        Map<String, Object> map = buscarNoMonolito("/os/" + osId);
        if (map != null && map.get("segmento") instanceof Map) {
            Map<String, Object> segMap = (Map<String, Object>) map.get("segmento");
            return convertToLong(segMap.get("id"));
        }
        return null;
    }

    private List<String> getUrlsMonolito() {
        return List.of(monolithUrl, "http://inprout-monolito:8080", "http://inprout-monolito-homolog:8080", "http://localhost:8080", "http://host.docker.internal:8080");
    }

    private Map<String, Object> buscarNoMonolito(String path) {
        String pathClean = path.startsWith("/") ? path : "/" + path;
        for (String baseUrl : getUrlsMonolito()) {
            if (baseUrl == null || baseUrl.isBlank()) continue;
            try {
                String urlBaseLimpa = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
                String fullUrl = urlBaseLimpa + pathClean;
                ResponseEntity<Map> response = restTemplate.exchange(fullUrl, HttpMethod.GET, createHttpEntity(null), Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return (Map<String, Object>) response.getBody();
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private HttpEntity<Object> createHttpEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            String authHeader = attributes.getRequest().getHeader("Authorization");
            if (authHeader != null) headers.set("Authorization", authHeader);
        }
        return new HttpEntity<>(body, headers);
    }

    @Transactional
    public void processarLote(DecisaoLoteDTO dto, String acao, String role) {
        List<ItemSolicitacao> itens = itemSolicitacaoRepository.findAllById(dto.ids());
        Set<Solicitacao> solicitacoesAfetadas = new HashSet<>();
        Map<Long, BigDecimal> valoresParaIntegrar = new HashMap<>();
        for (ItemSolicitacao item : itens) {
            if ("APROVAR".equalsIgnoreCase(acao)) {
                if (item.getStatusItem() == StatusItem.PENDENTE) {
                    item.setStatusItem(StatusItem.APROVADO);
                    solicitacoesAfetadas.add(item.getSolicitacao());
                    if (isFinanceiro(role)) {
                        BigDecimal valorItem = calcularCustoItem(item);
                        Long osId = item.getSolicitacao().getOsId();
                        if (osId != null) valoresParaIntegrar.merge(osId, valorItem, BigDecimal::add);
                    }
                }
            } else if ("REJEITAR".equalsIgnoreCase(acao)) {
                if (item.getStatusItem() == StatusItem.PENDENTE) {
                    item.setStatusItem(StatusItem.REPROVADO);
                    item.setMotivoRecusa(dto.observacao());
                    Material m = item.getMaterial();
                    m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
                    materialRepository.save(m);
                    solicitacoesAfetadas.add(item.getSolicitacao());
                }
            }
        }
        itemSolicitacaoRepository.saveAll(itens);
        valoresParaIntegrar.forEach(this::atualizarFinanceiroMonolito);
        for (Solicitacao s : solicitacoesAfetadas) { avancarFase(s, role); }
    }

    @Transactional
    public void decidirItem(Long itemId, String acao, String observacao, String role) {
        ItemSolicitacao item = itemSolicitacaoRepository.findById(itemId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (item.getStatusItem() != StatusItem.PENDENTE) return;
        if ("REJEITAR".equalsIgnoreCase(acao)) {
            item.setStatusItem(StatusItem.REPROVADO);
            item.setMotivoRecusa(observacao);
            Material m = item.getMaterial();
            m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
            materialRepository.save(m);
        } else {
            item.setStatusItem(StatusItem.APROVADO);
            if (isFinanceiro(role) && item.getSolicitacao().getOsId() != null) {
                BigDecimal valor = calcularCustoItem(item);
                atualizarFinanceiroMonolito(item.getSolicitacao().getOsId(), valor);
            }
        }
        itemSolicitacaoRepository.save(item);
        avancarFase(item.getSolicitacao(), role);
    }

    private void avancarFase(Solicitacao s, String role) {
        boolean existePendente = s.getItens().stream().anyMatch(i -> i.getStatusItem() == StatusItem.PENDENTE);
        if (existePendente) { solicitacaoRepository.save(s); return; }
        boolean todosRejeitados = s.getItens().stream().allMatch(i -> i.getStatusItem() == StatusItem.REPROVADO);
        if (todosRejeitados) { s.setStatus(StatusSolicitacao.REPROVADO); solicitacaoRepository.save(s); return; }
        if (role.toUpperCase().contains("COORDINATOR") || role.toUpperCase().contains("COORDENADOR") || role.toUpperCase().contains("MANAGER")) {
            s.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
            s.getItens().stream().filter(i -> i.getStatusItem() == StatusItem.APROVADO).forEach(i -> i.setStatusItem(StatusItem.PENDENTE));
        } else if (isFinanceiro(role)) {
            s.setStatus(StatusSolicitacao.APROVADO);
        }
        solicitacaoRepository.save(s);
    }

    private void atualizarFinanceiroMonolito(Long osId, BigDecimal valor) {
        if (valor == null || valor.compareTo(BigDecimal.ZERO) == 0) return;
        List<String> baseUrls = getUrlsMonolito();
        boolean sucesso = false;
        for (String base : baseUrls) {
            if (sucesso) break;
            try {
                String url = (base.endsWith("/") ? base : base + "/") + "os/" + osId + "/adicionar-custo-material";
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<BigDecimal> request = new HttpEntity<>(valor, headers);
                restTemplate.postForEntity(url, request, Void.class);
                sucesso = true;
            } catch (Exception ignored) { }
        }
    }

    private boolean isFinanceiro(String role) { return role != null && (role.toUpperCase().contains("CONTROLLER") || role.toUpperCase().contains("ADMIN")); }
    private BigDecimal calcularCustoItem(ItemSolicitacao item) {
        BigDecimal preco = item.getMaterial().getCustoMedioPonderado() != null ? item.getMaterial().getCustoMedioPonderado() : BigDecimal.ZERO;
        BigDecimal qtd = item.getQuantidadeSolicitada() != null ? item.getQuantidadeSolicitada() : BigDecimal.ZERO;
        return preco.multiply(qtd);
    }
    private String firstNonBlank(String... v) { for (String s : v) if (s != null && !s.isBlank()) return s; return null; }
    private String asString(Object o) { return o == null ? null : String.valueOf(o).trim(); }
    private Long convertToLong(Object o) { if (o instanceof Number) return ((Number) o).longValue(); try { return Long.valueOf(asString(o)); } catch (Exception e) { return null; } }
}