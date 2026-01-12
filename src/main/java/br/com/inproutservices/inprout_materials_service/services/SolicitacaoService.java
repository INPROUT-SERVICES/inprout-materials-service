package br.com.inproutservices.inprout_materials_service.services;

import br.com.inproutservices.inprout_materials_service.dtos.CriarSolicitacaoLoteDTO; // Importe o DTO correto
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
                              ItemSolicitacaoRepository itemSolicitacaoRepository, // <--- Adicione aqui
                              RestTemplate restTemplate) {
        this.solicitacaoRepository = solicitacaoRepository;
        this.materialRepository = materialRepository;
        this.itemSolicitacaoRepository = itemSolicitacaoRepository; // <--- E aqui
        this.restTemplate = restTemplate;
    }

    // --- CORREÇÃO: Método chamado pelo Controller para criar via CMS ---
    @Transactional
    public List<Solicitacao> criarSolicitacaoEmLote(CriarSolicitacaoLoteDTO dto) {
        // Cria uma única solicitação pai para o lote
        Solicitacao solicitacao = new Solicitacao();
        solicitacao.setOsId(dto.osId());
        solicitacao.setLpuId(dto.lpuItemId() != null ? dto.lpuItemId() : 0L);
        solicitacao.setSolicitanteId(dto.solicitanteId());
        solicitacao.setJustificativa(dto.observacoes());
        solicitacao.setStatus(StatusSolicitacao.PENDENTE_COORDENADOR);
        solicitacao.setDataSolicitacao(LocalDateTime.now());

        // Inicializa a lista de itens se estiver nula
        if (solicitacao.getItens() == null) {
            solicitacao.setItens(new ArrayList<>());
        }

        for (CriarSolicitacaoLoteDTO.ItemLoteRequest itemDto : dto.itens()) {
            Material material = materialRepository.findById(itemDto.materialId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Material não encontrado: " + itemDto.materialId()));

            // Reserva/Baixa Estoque Imediata (Regra de Negócio: segura o material na criação)
            if (material.getSaldoFisico().compareTo(itemDto.quantidade()) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Saldo insuficiente para o material: " + material.getDescricao());
            }
            material.setSaldoFisico(material.getSaldoFisico().subtract(itemDto.quantidade()));
            materialRepository.save(material);

            ItemSolicitacao item = new ItemSolicitacao(solicitacao, material, itemDto.quantidade());
            solicitacao.getItens().add(item);
        }

        Solicitacao salva = solicitacaoRepository.save(solicitacao);
        return List.of(salva); // Retorna lista para manter compatibilidade com controller
    }

    // --- LISTAGEM ---
    public List<SolicitacaoResponseDTO> listarPendentes(String userRole) {
        List<Solicitacao> todas = solicitacaoRepository.findAll();
        // Filtra em memória (ideal seria filtrar no banco repository.findByStatusIn(...))
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

    // --- APROVAÇÃO E REJEIÇÃO (Lógica de Custos Aqui) ---
    @Transactional
    public void processarLote(DecisaoLoteDTO dto, String acao, String role) {
        List<Solicitacao> solicitacoes = solicitacaoRepository.findAllById(dto.ids());
        String roleUpper = role.toUpperCase();

        for (Solicitacao s : solicitacoes) {
            if ("APROVAR".equals(acao)) {
                if (roleUpper.equals("COORDINATOR") || roleUpper.equals("MANAGER")) {
                    // Coordenador aprova -> Vai para Controller
                    s.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
                }
                else if (roleUpper.equals("CONTROLLER") || roleUpper.equals("ADMIN")) {
                    // Controller aprova -> Finaliza e GERA CUSTO
                    aprovarFinal(s);
                }
            } else if ("REJEITAR".equals(acao)) {
                rejeitarSolicitacao(s, dto.observacao());
            }
        }
        solicitacaoRepository.saveAll(solicitacoes);
    }

    private void aprovarFinal(Solicitacao s) {
        s.setStatus(StatusSolicitacao.APROVADO);

        BigDecimal custoTotal = BigDecimal.ZERO;
        for (ItemSolicitacao item : s.getItens()) {
            // SÓ SOMA O CUSTO SE O ITEM ESTIVER APROVADO
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
        s.setStatus(StatusSolicitacao.REPROVADO); // ou REJEITADO_...
        // s.setObservacao(motivo); // Se tiver o campo observação na entidade

        // IMPORTANTE: Devolver o saldo ao estoque se for rejeitado
        for (ItemSolicitacao item : s.getItens()) {
            Material m = item.getMaterial();
            m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
            materialRepository.save(m);
        }
    }

    private void atualizarFinanceiroMonolito(Long osId, BigDecimal valor) {
        try {
            // Ajuste a URL para bater com sua API do Monólito
            String url = monolithUrl + "/api/os/" + osId + "/adicionar-custo-material";
            // Se o endpoint esperar um objeto JSON wrapper, ajuste aqui. Abaixo envio o valor direto.
            restTemplate.postForLocation(url, valor);
            // Obs: postForLocation ou postForEntity dependendo do retorno da API antiga
        } catch (Exception e) {
            System.err.println("ERRO INTEGRACAO OS: " + e.getMessage());
        }
    }

    private SolicitacaoResponseDTO converterParaDTO(Solicitacao s) {
        // Mesma lógica de conversão que você já tinha...
        OsDTO osDto = new OsDTO(s.getOsId(), "OS " + s.getOsId(), new SegmentoDTO(0L, "-"));
        LpuDTO lpuDto = new LpuDTO(s.getLpuId(), "Item LPU", "-");
        String nome = "Solicitante ID " + s.getSolicitanteId();

        try {
            if (s.getOsId() != null) {
                // Tenta buscar, se falhar usa o default
                try { osDto = restTemplate.getForObject(monolithUrl + "/os/" + s.getOsId(), OsDTO.class); } catch(Exception e){}
            }
            // ... lógica similar para LPU e Usuario ...
        } catch (Exception e) {
            // Silencia erro de conexão para não travar listagem
        }

        return new SolicitacaoResponseDTO(
                s.getId(),
                s.getDataSolicitacao(),
                s.getJustificativa(),
                s.getStatus(),
                nome,
                osDto,
                lpuDto,
                s.getItens()
        );
    }

    @Transactional
    public void aprovarParcialmente(Long solicitacaoId, List<Long> idsItensAprovados, String role) {
        Solicitacao solicitacao = solicitacaoRepository.findById(solicitacaoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Solicitação não encontrada"));

        // 1. Identificar itens que NÃO foram aprovados (Rejeitados)
        List<ItemSolicitacao> itensRejeitados = solicitacao.getItens().stream()
                .filter(item -> !idsItensAprovados.contains(item.getId()))
                .collect(Collectors.toList());

        // 2. Devolver saldo ao estoque e remover da solicitação
        for (ItemSolicitacao item : itensRejeitados) {
            Material material = item.getMaterial();
            // Devolve a quantidade reservada
            material.setSaldoFisico(material.getSaldoFisico().add(item.getQuantidadeSolicitada()));
            materialRepository.save(material);

            // Remove da lista da solicitação (O orphanRemoval=true na entidade fará o delete no banco)
            solicitacao.getItens().remove(item);
        }

        // 3. Verifica se sobrou algo
        if (solicitacao.getItens().isEmpty()) {
            // Se rejeitou todos os itens, o pedido vira REPROVADO automaticamente
            solicitacao.setStatus(StatusSolicitacao.REPROVADO);
            solicitacao.setObservacaoReprovacao("Todos os itens foram rejeitados individualmente.");
        } else {
            // Se sobrou itens, segue o fluxo normal de aprovação
            if ("COORDINATOR".equals(role)) {
                solicitacao.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
            } else {
                aprovarFinal(solicitacao); // Seu método existente que gera custo e finaliza
            }
        }

        solicitacaoRepository.save(solicitacao);
    }

    @Transactional
    public void decidirItem(Long itemId, String acao, String observacao, String role) {
        // 1. Validações de Negócio
        if (role == null || role.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Permissão não identificada (Role ausente).");
        }

        // Se for REJEITAR, a observação é obrigatória (Regra de Negócio comum)
        if ("REJEITAR".equals(acao) && (observacao == null || observacao.trim().isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Para rejeitar um item, é obrigatório informar o motivo.");
        }

        ItemSolicitacao item = itemSolicitacaoRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item de solicitação não encontrado"));

        Solicitacao solicitacao = item.getSolicitacao();
        String roleUpper = role.toUpperCase();

        // 2. Lógica de Decisão (A mesma que desenhamos antes, agora segura)
        if ("REJEITAR".equals(acao)) {
            item.setStatusItem(StatusItem.REPROVADO);
            item.setMotivoRecusa(observacao);

            // Devolve ao estoque imediatamente
            Material m = item.getMaterial();
            m.setSaldoFisico(m.getSaldoFisico().add(item.getQuantidadeSolicitada()));
            materialRepository.save(m);

        } else if ("APROVAR".equals(acao)) {
            item.setStatusItem(StatusItem.APROVADO);
            // Limpa motivo de recusa caso tenha sido recusado anteriormente e re-aprovado (revisão)
            item.setMotivoRecusa(null);
        } else {
            // Caso a validação do DTO falhe ou algo estranho chegue aqui
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ação inválida: " + acao);
        }

        itemSolicitacaoRepository.save(item);

        // 3. Verifica o Pai (Solicitação)
        verificarEAvancarFase(solicitacao, roleUpper);
    }

    private void verificarEAvancarFase(Solicitacao s, String role) {
        boolean todosProcessados = s.getItens().stream()
                .allMatch(i -> i.getStatusItem() != StatusItem.PENDENTE);

        // Se ainda tem item pendente nessa solicitação, o Pai não muda de status
        // (Isso atende ao requisito: "deixar o outro parado")
        if (!todosProcessados) return;

        // Se todos foram decididos (Aprovados ou Reprovados)

        if (role.equals("COORDINATOR") || role.equals("MANAGER")) {
            // Se o Coordenador terminou tudo, vai para o Controller
            // Mas só se sobrou algum item APROVADO. Se tudo foi reprovado, o pedido morre.
            boolean temItemAprovado = s.getItens().stream().anyMatch(i -> i.getStatusItem() == StatusItem.APROVADO);

            if (temItemAprovado) {
                s.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
                // Resetar status dos itens para PENDENTE para o Controller avaliar?
                // OU manter APROVADO e o Controller só valida os aprovados?
                // Sugestão: Manter APROVADO e o Controller revalida.
                // Mas para simplicidade visual, vamos resetar o status dos itens APROVADOS para PENDENTE
                // para que o Controller tenha que votar neles novamente.
                s.getItens().stream()
                        .filter(i -> i.getStatusItem() == StatusItem.APROVADO)
                        .forEach(i -> i.setStatusItem(StatusItem.PENDENTE));
            } else {
                s.setStatus(StatusSolicitacao.REPROVADO); // Tudo foi recusado
            }

        } else if (role.equals("CONTROLLER") || role.equals("ADMIN")) {
            // Controller finalizou tudo
            boolean temItemAprovado = s.getItens().stream().anyMatch(i -> i.getStatusItem() == StatusItem.APROVADO);

            if (temItemAprovado) {
                aprovarFinal(s); // Método que já existe, mas precisa ser ajustado para somar SÓ itens APROVADOS
            } else {
                s.setStatus(StatusSolicitacao.REPROVADO);
            }
        }

        solicitacaoRepository.save(s);
    }
}