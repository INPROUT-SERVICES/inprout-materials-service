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

    private final Map<Long, Long> cacheSegmentoOs = new HashMap<>();

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
        System.out.println(">>> LISTAR PENDENTES (V2) | Role: " + userRole + " | ID: " + userId);

        if (userRole == null) return new ArrayList<>();
        String roleUpper = userRole.toUpperCase();

        if (roleUpper.contains("ADMIN")) {
            return solicitacaoRepository.findAllPendentesAdmin().stream()
                    .map(this::converterParaDTO)
                    .collect(Collectors.toList());
        }
        else if (roleUpper.contains("CONTROLLER")) {
            return solicitacaoRepository.findByStatus(StatusSolicitacao.PENDENTE_CONTROLLER).stream()
                    .map(this::converterParaDTO)
                    .collect(Collectors.toList());
        }
        else if (roleUpper.contains("COORDINATOR") || roleUpper.contains("COORDENADOR") || roleUpper.contains("MANAGER")) {

            List<Long> segmentosUsuario = buscarSegmentosDoUsuario(userId);
            System.out.println(">>> Segmentos do Usuario (Processado): " + segmentosUsuario);

            if (segmentosUsuario.isEmpty()) {
                System.out.println(">>> ALERTA: Usuario sem segmentos. Retornando vazio.");
                return new ArrayList<>();
            }

            List<Solicitacao> todasPendentes = solicitacaoRepository.findByStatus(StatusSolicitacao.PENDENTE_COORDENADOR);

            return todasPendentes.stream()
                    .filter(s -> validarOuAtualizarSegmento(s, segmentosUsuario))
                    .map(this::converterParaDTO)
                    .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }

    private boolean validarOuAtualizarSegmento(Solicitacao s, List<Long> segmentosUsuario) {
        Long segmentoId = s.getSegmentoId();

        if (segmentoId != null) {
            return segmentosUsuario.contains(segmentoId);
        }

        System.out.println(">>> Solicitacao " + s.getId() + " com segmento NULL. Buscando...");

        Long segmentoRecuperado = null;
        if (s.getLpuId() != null && s.getLpuId() > 0) {
            segmentoRecuperado = buscarSegmentoDoItem(s.getLpuId());
        }
        if (segmentoRecuperado == null) {
            segmentoRecuperado = buscarSegmentoDaOs(s.getOsId());
        }

        if (segmentoRecuperado != null) {
            System.out.println(">>> Segmento recuperado: " + segmentoRecuperado + ". Atualizando banco...");
            s.setSegmentoId(segmentoRecuperado);
            solicitacaoRepository.save(s);

            return segmentosUsuario.contains(segmentoRecuperado);
        }

        return false;
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

        return filtradas.stream()
                .sorted((a, b) -> b.getDataSolicitacao().compareTo(a.getDataSolicitacao()))
                .limit(300)
                .map(this::converterParaDTO)
                .collect(Collectors.toList());
    }

    // --- MÉTODOS DE BUSCA E INTEGRAÇÃO ---

    // === CORREÇÃO CRÍTICA AQUI ===
    private List<Long> buscarSegmentosDoUsuario(Long userId) {
        Map<String, Object> map = buscarNoMonolito("/usuarios/" + userId);
        List<Long> ids = new ArrayList<>();

        if (map == null) return ids;

        // Verifica se existe o campo "segmentos" e se é uma lista
        if (map.containsKey("segmentos") && map.get("segmentos") instanceof List) {
            List<?> lista = (List<?>) map.get("segmentos");
            for (Object item : lista) {
                // Caso 1: O monólito devolve objetos (ex: [{"id": 1, ...}])
                if (item instanceof Map) {
                    Long id = convertToLong(((Map<?, ?>) item).get("id"));
                    if (id != null) ids.add(id);
                }
                // Caso 2: O monólito devolve apenas IDs (ex: [1, 2, 3]) <--- ESTE É O SEU CASO
                else if (item instanceof Number) {
                    ids.add(((Number) item).longValue());
                }
            }
        }
        // Fallback: Verifica se existe "segmento" (singular) como objeto
        else if (map.containsKey("segmento") && map.get("segmento") instanceof Map) {
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
        if (osId == null) return null;
        if (cacheSegmentoOs.containsKey(osId)) return cacheSegmentoOs.get(osId);

        Map<String, Object> map = buscarNoMonolito("/os/" + osId);
        if (map != null && map.get("segmento") instanceof Map) {
            Map<String, Object> segMap = (Map<String, Object>) map.get("segmento");
            Long segId = convertToLong(segMap.get("id"));
            cacheSegmentoOs.put(osId, segId);
            return segId;
        }
        return null;
    }

    private List<String> getUrlsMonolito() {
        return List.of(
                monolithUrl,
                "http://inprout-monolito:8080",
                "http://inprout-monolito-homolog:8080",
                "http://localhost:8080",
                "http://host.docker.internal:8080"
        );
    }

    private Map<String, Object> buscarNoMonolito(String path) {
        String pathClean = path.startsWith("/") ? path : "/" + path;
        for (String baseUrl : getUrlsMonolito()) {
            if (baseUrl == null || baseUrl.isBlank()) continue;
            try {
                String urlBaseLimpa = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
                String fullUrl = urlBaseLimpa + pathClean;

                ResponseEntity<Map> response = restTemplate.exchange(
                        fullUrl,
                        HttpMethod.GET,
                        createHttpEntity(null),
                        Map.class
                );

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
            jakarta.servlet.http.HttpServletRequest request = attributes.getRequest();
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null) {
                headers.set("Authorization", authHeader);
            }
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
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                org.springframework.http.HttpEntity<BigDecimal> request = new org.springframework.http.HttpEntity<>(valor, headers);
                restTemplate.postForEntity(url, request, Void.class);
                sucesso = true;
            } catch (Exception ignored) { }
        }
    }

    private SolicitacaoResponseDTO converterParaDTO(Solicitacao s) {
        return new SolicitacaoResponseDTO(
                s.getId(), s.getDataSolicitacao(), s.getJustificativa(), s.getStatus(),
                montarNomeSolicitante(s.getSolicitanteId()),
                montarOsDTO(s.getOsId()),
                montarLpuDTO(s.getLpuId()),
                s.getItens()
        );
    }

    private OsDTO montarOsDTO(Long osId) {
        SegmentoDTO fallbackSeg = new SegmentoDTO(0L, "-");
        Map<String, Object> map = buscarNoMonolito("/os/" + osId);
        if (map != null) {
            String num = firstNonBlank(asString(map.get("os")), asString(map.get("numeroOS")), String.valueOf(osId));
            SegmentoDTO seg = fallbackSeg;
            if (map.get("segmento") instanceof Map) {
                Map<String, Object> segMap = (Map<String, Object>) map.get("segmento");
                seg = new SegmentoDTO(convertToLong(segMap.get("id")), firstNonBlank(asString(segMap.get("nome")), "-"));
            }
            return new OsDTO(convertToLong(map.get("id")), num, seg);
        }
        return new OsDTO(osId, String.valueOf(osId), fallbackSeg);
    }

    private LpuDTO montarLpuDTO(Long lpuId) {
        if (lpuId == null || lpuId == 0) return new LpuDTO(0L, "Não informado", "-", "-");

        Map<String, Object> map = buscarNoMonolito("/os/detalhes/" + lpuId);

        return new LpuDTO(
                lpuId,
                map != null ? asString(map.get("objetoContratado")) : "Não encontrado",
                "-",
                map != null ? asString(map.get("site")) : "-"
        );
    }

    private String montarNomeSolicitante(Long id) {
        if (id == null) return "Não informado";
        Map<String, Object> map = buscarNoMonolito("/usuarios/" + id);
        return (map != null && map.get("nome") != null) ? asString(map.get("nome")) : "Solicitante #" + id;
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