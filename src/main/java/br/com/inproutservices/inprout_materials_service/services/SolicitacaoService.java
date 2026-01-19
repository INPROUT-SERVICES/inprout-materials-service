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

        List<Solicitacao> solicitacoes = new ArrayList<>();

        // 1. REGRA DE ADMIN (Vê tudo que tem pendência real nos itens)
        if ("ADMIN".equalsIgnoreCase(userRole)) {
            solicitacoes = solicitacaoRepository.findAllPendentesAdmin();
        }
        // 2. REGRA DE CONTROLLER (Vê o que já passou pelo coordenador)
        else if ("CONTROLLER".equalsIgnoreCase(userRole)) {
            solicitacoes = solicitacaoRepository.findByStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
        }
        // 3. REGRA DE COORDENADOR (Vê fase inicial + Filtro de Segmento)
        else if ("COORDINATOR".equalsIgnoreCase(userRole)) {
            List<Solicitacao> doCoordenador = solicitacaoRepository.findByStatus(StatusSolicitacao.PENDENTE_COORDENADOR);

            // Filtra por segmento
            Long segmentoUsuario = buscarSegmentoDoUsuario(userId);
            solicitacoes = doCoordenador.stream()
                    .filter(s -> pertenceAoSegmento(s, segmentoUsuario))
                    .collect(Collectors.toList());
        }

        // Converte a lista final para DTO
        return solicitacoes.stream()
                .map(this::converterParaDTO)
                .collect(Collectors.toList());
    }gii

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

        // Mapa para acumular valores por OS (para enviar de uma vez por OS e não por item)
        Map<Long, BigDecimal> valoresParaIntegrar = new HashMap<>();

        for (ItemSolicitacao item : itens) {
            if ("APROVAR".equalsIgnoreCase(acao)) {
                if (item.getStatusItem() == StatusItem.PENDENTE) {
                    item.setStatusItem(StatusItem.APROVADO);
                    solicitacoesAfetadas.add(item.getSolicitacao());

                    // --- MUDANÇA: SOMA IMEDIATA SE FOR CONTROLLER/ADMIN ---
                    if (isFinanceiro(role)) {
                        BigDecimal valorItem = calcularCustoItem(item);
                        Long osId = item.getSolicitacao().getOsId();
                        if (osId != null) {
                            valoresParaIntegrar.merge(osId, valorItem, BigDecimal::add);
                        }
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

        // Envia os valores acumulados para o Monólito
        valoresParaIntegrar.forEach(this::atualizarFinanceiroMonolito);

        for (Solicitacao s : solicitacoesAfetadas) {
            avancarFase(s, role);
        }
    }

    @Transactional
    public void decidirItem(Long itemId, String acao, String observacao, String role) {
        System.out.println(">>> DECIDIR ITEM INICIADO. ID: " + itemId + " | Ação: " + acao + " | Role: " + role);

        ItemSolicitacao item = itemSolicitacaoRepository.findById(itemId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // Evitar processar item já finalizado
        if (item.getStatusItem() != StatusItem.PENDENTE) {
            return;
        }

        if ("REJEITAR".equalsIgnoreCase(acao)) {
            item.setStatusItem(StatusItem.REPROVADO);
            item.setMotivoRecusa(observacao);
            Material m = item.getMaterial();
            m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
            materialRepository.save(m);
        } else {
            item.setStatusItem(StatusItem.APROVADO);

            // --- MUDANÇA: ENVIO IMEDIATO SE FOR CONTROLLER/ADMIN ---
            if (isFinanceiro(role) && item.getSolicitacao().getOsId() != null) {
                BigDecimal valor = calcularCustoItem(item);
                atualizarFinanceiroMonolito(item.getSolicitacao().getOsId(), valor);
            }
        }
        itemSolicitacaoRepository.save(item);
        avancarFase(item.getSolicitacao(), role);
    }

    private void avancarFase(Solicitacao s, String role) {
        System.out.println(">>> VERIFICANDO AVANÇO DE FASE. Status Atual: " + s.getStatus());

        // 1. Verifica se tem algum pendente
        boolean existePendente = s.getItens().stream().anyMatch(i -> i.getStatusItem() == StatusItem.PENDENTE);
        if (existePendente) {
            // Se tem pendente, apenas salvamos o estado atual dos itens, mas não finalizamos a Solicitação global
            System.out.println(">>> AINDA HÁ ITENS PENDENTES. STATUS DA SOLICITAÇÃO MANTIDO.");
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

        // 3. Lógica de papéis
        if ("COORDINATOR".equalsIgnoreCase(role)) {
            System.out.println(">>> COORDENADOR APROVOU. AVANÇANDO PARA CONTROLLER.");
            s.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
            // Reseta os itens aprovados pelo coordenador para Pendente, para o Controller aprovar financeiramente
            s.getItens().stream()
                    .filter(i -> i.getStatusItem() == StatusItem.APROVADO)
                    .forEach(i -> i.setStatusItem(StatusItem.PENDENTE));

        } else if (isFinanceiro(role)) {
            // --- MUDANÇA: APENAS FINALIZA O STATUS ---
            // O valor financeiro já foi enviado item a item nos métodos 'decidirItem' e 'processarLote'
            System.out.println(">>> CONTROLLER/ADMIN FINALIZOU TODOS OS ITENS. STATUS -> APROVADO.");
            s.setStatus(StatusSolicitacao.APROVADO);
        }

        solicitacaoRepository.save(s);
    }

    // --- CORREÇÃO DA INTEGRAÇÃO HTTP ---
    private void atualizarFinanceiroMonolito(Long osId, BigDecimal valor) {
        if (valor == null || valor.compareTo(BigDecimal.ZERO) == 0) return;

        List<String> urlsToTry = new ArrayList<>();
        // Tenta nome do serviço Docker
        urlsToTry.add("http://inprout-monolito-homolog:8080/os/" + osId + "/adicionar-custo-material");
        // Tenta URL configurada (backup para local)
        if (monolithUrl != null && !monolithUrl.contains("inprout-monolito-homolog")) {
            urlsToTry.add(monolithUrl + "/os/" + osId + "/adicionar-custo-material");
        }

        boolean sucesso = false;

        for (String url : urlsToTry) {
            if (sucesso) break;
            try {
                System.out.println(">>> TENTANDO INTEGRAR " + valor + " EM: " + url);
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                org.springframework.http.HttpEntity<BigDecimal> request = new org.springframework.http.HttpEntity<>(valor, headers);

                // CORREÇÃO: Usar postForEntity para garantir que aceitamos 200 OK sem body de retorno
                restTemplate.postForEntity(url, request, Void.class);

                System.out.println(">>> SUCESSO! VALOR INTEGRADO.");
                sucesso = true;
            } catch (Exception e) {
                System.out.println(">>> FALHA EM " + url + ": " + e.getMessage());
            }
        }
    }

    // --- MÉTODOS AUXILIARES ---

    private boolean isFinanceiro(String role) {
        return "CONTROLLER".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);
    }

    private BigDecimal calcularCustoItem(ItemSolicitacao item) {
        BigDecimal preco = item.getMaterial().getCustoMedioPonderado() != null ? item.getMaterial().getCustoMedioPonderado() : BigDecimal.ZERO;
        BigDecimal qtd = item.getQuantidadeSolicitada() != null ? item.getQuantidadeSolicitada() : BigDecimal.ZERO;
        return preco.multiply(qtd);
    }

    // --- MÉTODOS DE DTO (Mantidos) ---
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