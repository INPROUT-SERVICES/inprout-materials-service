// inprout-services/inprout-materials-service/inprout-materials-service-86d2030c50623b44027549cb71c59c71980ee568/src/main/java/br/com/inproutservices/inprout_materials_service/services/SolicitacaoService.java

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
            solicitacao.getItens().add(item);
        }

        Solicitacao salva = solicitacaoRepository.save(solicitacao);
        return List.of(salva);
    }

    // --- LISTAGEM ---
    public List<SolicitacaoResponseDTO> listarPendentes(String userRole) {
        List<Solicitacao> todas = solicitacaoRepository.findAll();
        return todas.stream()
                .filter(s -> ehPendenteParaRole(s, userRole))
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

    // --- DECISÃO EM LOTE (Aprovar Tudo / Rejeitar Tudo) ---
    @Transactional
    public void processarLote(DecisaoLoteDTO dto, String acao, String role) {
        List<Solicitacao> solicitacoes = solicitacaoRepository.findAllById(dto.ids());
        String roleUpper = role.toUpperCase();

        for (Solicitacao s : solicitacoes) {
            if ("APROVAR".equals(acao)) {
                // Ao aprovar o lote inteiro, marcamos todos os itens como aprovados também
                s.getItens().forEach(item -> item.setStatusItem(StatusItem.APROVADO));

                if (roleUpper.equals("COORDINATOR") || roleUpper.equals("MANAGER")) {
                    s.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
                    // Reseta itens para controller avaliar novamente
                    s.getItens().forEach(item -> item.setStatusItem(StatusItem.PENDENTE));
                } else if (roleUpper.equals("CONTROLLER") || roleUpper.equals("ADMIN")) {
                    aprovarFinal(s);
                }
            } else if ("REJEITAR".equals(acao)) {
                rejeitarSolicitacao(s, dto.observacao());
            }
        }
        solicitacaoRepository.saveAll(solicitacoes);
    }

    // --- DECISÃO ITEM A ITEM (Granular) ---
    @Transactional
    public void decidirItem(Long itemId, String acao, String observacao, String role) {
        if (role == null || role.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permissão não identificada (Role ausente).");
        }

        if ("REJEITAR".equals(acao) && (observacao == null || observacao.trim().isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Para rejeitar um item, é obrigatório informar o motivo.");
        }

        ItemSolicitacao item = itemSolicitacaoRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item de solicitação não encontrado"));

        Solicitacao solicitacao = item.getSolicitacao();
        String roleUpper = role.toUpperCase();

        if ("REJEITAR".equals(acao)) {
            item.setStatusItem(StatusItem.REPROVADO);
            item.setMotivoRecusa(observacao);

            // Devolve ao estoque imediatamente
            Material m = item.getMaterial();
            m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
            materialRepository.save(m);

        } else if ("APROVAR".equals(acao)) {
            item.setStatusItem(StatusItem.APROVADO);
            item.setMotivoRecusa(null);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ação inválida: " + acao);
        }

        itemSolicitacaoRepository.save(item);

        // Verifica se a solicitação inteira pode andar
        verificarEAvancarFase(solicitacao, roleUpper);
    }

    private void verificarEAvancarFase(Solicitacao s, String role) {
        boolean todosProcessados = s.getItens().stream()
                .allMatch(i -> i.getStatusItem() != StatusItem.PENDENTE);

        // Se ainda tem item pendente, não muda o status do pai
        if (!todosProcessados) return;

        if (role.equals("COORDINATOR") || role.equals("MANAGER")) {
            boolean temItemAprovado = s.getItens().stream().anyMatch(i -> i.getStatusItem() == StatusItem.APROVADO);

            if (temItemAprovado) {
                s.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
                // Resetar itens aprovados para PENDENTE para o Controller avaliar
                s.getItens().stream()
                        .filter(i -> i.getStatusItem() == StatusItem.APROVADO)
                        .forEach(i -> i.setStatusItem(StatusItem.PENDENTE));
            } else {
                s.setStatus(StatusSolicitacao.REPROVADO);
            }

        } else if (role.equals("CONTROLLER") || role.equals("ADMIN")) {
            boolean temItemAprovado = s.getItens().stream().anyMatch(i -> i.getStatusItem() == StatusItem.APROVADO);

            if (temItemAprovado) {
                aprovarFinal(s);
            } else {
                s.setStatus(StatusSolicitacao.REPROVADO);
            }
        }

        solicitacaoRepository.save(s);
    }

    // --- MÉTODOS AUXILIARES DE FINALIZAÇÃO ---

    private void aprovarFinal(Solicitacao s) {
        s.setStatus(StatusSolicitacao.APROVADO);

        BigDecimal custoTotal = BigDecimal.ZERO;
        for (ItemSolicitacao item : s.getItens()) {
            if (item.getStatusItem() == StatusItem.APROVADO && item.getMaterial().getCustoMedioPonderado() != null) {
                BigDecimal custoItem = item.getMaterial().getCustoMedioPonderado().multiply(item.getQuantidadeSolicitada());
                custoTotal = custoTotal.add(custoItem);
            }
        }

        if (s.getOsId() != null && custoTotal.compareTo(BigDecimal.ZERO) > 0) {
            atualizarFinanceiroMonolito(s.getOsId(), custoTotal);
        }
    }

    private void rejeitarSolicitacao(Solicitacao s, String motivo) {
        s.setStatus(StatusSolicitacao.REPROVADO);
        // Devolver o saldo ao estoque se for rejeitado totalmente
        for (ItemSolicitacao item : s.getItens()) {
            // Verifica se já não foi devolvido individualmente (caso misto)
            if (item.getStatusItem() != StatusItem.REPROVADO) {
                Material m = item.getMaterial();
                m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
                materialRepository.save(m);
                item.setStatusItem(StatusItem.REPROVADO);
            }
        }
    }

    private void atualizarFinanceiroMonolito(Long osId, BigDecimal valor) {
        try {
            String url = monolithUrl + "/api/os/" + osId + "/adicionar-custo-material";
            restTemplate.postForLocation(url, valor);
        } catch (Exception e) {
            System.err.println("ERRO INTEGRACAO OS: " + e.getMessage());
        }
    }

    // --- CONVERSÃO E INTEGRAÇÃO (DTOs) ---

    private SolicitacaoResponseDTO converterParaDTO(Solicitacao s) {
        OsDTO osDto = montarOsDTO(s.getOsId());
        LpuDTO lpuDto = montarLpuDTO(s.getLpuId()); // Agora busca o objeto contratado
        String nomeSolicitante = montarNomeSolicitante(s.getSolicitanteId()); // Agora busca o nome real

        return new SolicitacaoResponseDTO(
                s.getId(),
                s.getDataSolicitacao(),
                s.getJustificativa(),
                s.getStatus(),
                nomeSolicitante,
                osDto,
                lpuDto,
                s.getItens()
        );
    }

    @SuppressWarnings("unchecked")
    private OsDTO montarOsDTO(Long osId) {
        SegmentoDTO segmentoFallback = new SegmentoDTO(0L, "-");
        String osNumeroFallback = (osId == null) ? "N/A" : String.valueOf(osId);
        OsDTO fallback = new OsDTO(osId, osNumeroFallback, segmentoFallback);

        if (osId == null) return fallback;

        for (String base : monolithBaseUrls()) {
            String[] paths = new String[]{"/api/os/" + osId, "/os/" + osId};
            for (String path : paths) {
                Map<String, Object> map = tryGetMap(base + path);
                if (map == null || map.isEmpty()) continue;

                Long id = asLong(map.get("id"));
                if (id == null) id = osId;

                // Garante que busca o campo string 'os' corretamente
                String osNumero = firstNonBlank(
                        asString(map.get("os")), asString(map.get("numeroOS")),
                        asString(map.get("numero")), asString(map.get("codigo"))
                );
                if (osNumero == null) osNumero = osNumeroFallback;

                SegmentoDTO seg = segmentoFallback;
                Object segObj = map.get("segmento");
                if (segObj instanceof Map) {
                    Map<String, Object> segMap = (Map<String, Object>) segObj;
                    Long segId = asLong(segMap.get("id"));
                    String segNome = firstNonBlank(asString(segMap.get("nome")), asString(segMap.get("descricao")));
                    seg = new SegmentoDTO(segId == null ? 0L : segId, segNome == null ? "-" : segNome);
                }
                return new OsDTO(id, osNumero, seg);
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private LpuDTO montarLpuDTO(Long lpuId) {
        // ID do LPU aqui refere-se ao 'OsLpuDetalhe' (item da OS), de onde pegamos o Objeto Contratado.
        if (lpuId == null || lpuId == 0) return new LpuDTO(0L, "Contrato não informado", "-");

        for (String base : monolithBaseUrls()) {
            // Novo endpoint criado no monolito: /os/detalhes/{id}
            String url = base + "/os/detalhes/" + lpuId;
            Map<String, Object> map = tryGetMap(url);

            if (map != null && map.get("objetoContratado") != null) {
                String objContratado = asString(map.get("objetoContratado"));
                // Usamos o campo 'nome' do DTO para passar o Objeto Contratado para o Front
                return new LpuDTO(lpuId, objContratado, "-");
            }
        }
        return new LpuDTO(lpuId, "Objeto não encontrado", "-");
    }

    @SuppressWarnings("unchecked")
    private String montarNomeSolicitante(Long solicitanteId) {
        if (solicitanteId == null) return "Não informado";
        for (String base : monolithBaseUrls()) {
            // Novo endpoint criado: /usuarios/{id}
            String url = base + "/usuarios/" + solicitanteId;
            Map<String, Object> map = tryGetMap(url);

            if (map != null && map.get("nome") != null) {
                return asString(map.get("nome"));
            }
        }
        return "Solicitante #" + solicitanteId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryGetMap(String url) {
        try {
            return restTemplate.getForObject(url, Map.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> monolithBaseUrls() {
        List<String> bases = new ArrayList<>();
        if (monolithUrl != null && !monolithUrl.trim().isEmpty()) {
            bases.add(monolithUrl.trim());
        }
        if (!bases.contains("http://localhost:8080")) bases.add("http://localhost:8080");
        return bases;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private String asString(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return (s == null) ? null : s.trim();
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) return Long.valueOf(s);
        } catch (Exception e) { /* ignorar */ }
        return null;
    }
}