package br.com.inproutservices.inprout_materials_service.services;

import br.com.inproutservices.inprout_materials_service.dtos.DecisaoLoteDTO;
import br.com.inproutservices.inprout_materials_service.dtos.SolicitacaoLoteRequestDTO;
import br.com.inproutservices.inprout_materials_service.dtos.response.*;
import br.com.inproutservices.inprout_materials_service.entities.ItemSolicitacao;
import br.com.inproutservices.inprout_materials_service.entities.Material;
import br.com.inproutservices.inprout_materials_service.entities.Solicitacao;
import br.com.inproutservices.inprout_materials_service.enums.StatusSolicitacao;
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
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SolicitacaoService {

    private final SolicitacaoRepository solicitacaoRepository;
    private final MaterialRepository materialRepository;
    private final RestTemplate restTemplate;

    @Value("${app.monolith.url:http://app-backend:8080}")
    private String monolithUrl;

    public SolicitacaoService(SolicitacaoRepository solicitacaoRepository,
                              MaterialRepository materialRepository,
                              RestTemplate restTemplate) {
        this.solicitacaoRepository = solicitacaoRepository;
        this.materialRepository = materialRepository;
        this.restTemplate = restTemplate;
    }

    @Transactional
    public Solicitacao criarEmLote(SolicitacaoLoteRequestDTO dto) {
        // 1. Cria a Solicitação Pai
        Solicitacao solicitacao = new Solicitacao();
        solicitacao.setOsId(dto.osId());
        // Ajuste: Verifica se lpuItemId não é nulo antes de setar
        if (dto.lpuItemId() != null) {
            solicitacao.setLpuId(dto.lpuItemId());
        } else {
            solicitacao.setLpuId(0L); // Ou trate como preferir caso venha nulo
        }

        solicitacao.setSolicitanteId(dto.solicitanteId());
        solicitacao.setJustificativa(dto.observacoes());
        // CORREÇÃO: Usando o Enum correto
        solicitacao.setStatus(StatusSolicitacao.PENDENTE_COORDENADOR);
        solicitacao.setDataSolicitacao(LocalDateTime.now());

        BigDecimal custoTotalSolicitacao = BigDecimal.ZERO;

        // 2. Processa os Itens
        for (SolicitacaoLoteRequestDTO.ItemLoteDTO itemDto : dto.itens()) {
            Material material = materialRepository.findById(itemDto.materialId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Material não encontrado: " + itemDto.materialId()));

            // Validação de Saldo
            if (material.getSaldoFisico().compareTo(itemDto.quantidade()) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Saldo insuficiente para o material: " + material.getDescricao());
            }

            // Debitar Estoque
            material.setSaldoFisico(material.getSaldoFisico().subtract(itemDto.quantidade()));
            materialRepository.save(material);

            // Criar Item da Solicitação
            ItemSolicitacao item = new ItemSolicitacao(solicitacao, material, itemDto.quantidade());

            // Adiciona na lista da solicitação pai
            solicitacao.getItens().add(item);

            // Soma custo
            if (material.getCustoMedioPonderado() != null) {
                custoTotalSolicitacao = custoTotalSolicitacao.add(material.getCustoMedioPonderado().multiply(itemDto.quantidade()));
            }
        }

        // 3. Salva tudo (Cascade salva os itens)
        Solicitacao solicitacaoSalva = solicitacaoRepository.save(solicitacao);

        // 4. Integração com Monólito
        atualizarFinanceiroMonolito(dto.osId(), custoTotalSolicitacao);

        return solicitacaoSalva;
    }

    public List<Solicitacao> buscarPendenciasPorRole(String role, Long userId) {
        if (role == null) return List.of();

        // Normaliza a string para maiúsculo para evitar erro de case sensitive
        String roleUpper = role.trim().toUpperCase();

        if (roleUpper.equals("COORDINATOR") || roleUpper.equals("MANAGER")) {
            // Gestores veem o que está aguardando aprovação deles
            return solicitacaoRepository.findByStatus(StatusSolicitacao.PENDENTE_COORDENADOR);
        }
        else if (roleUpper.equals("CONTROLLER") || roleUpper.equals("ADMIN")) {
            // Controllers veem o que já passou pelo gestor e está pendente neles
            return solicitacaoRepository.findByStatus(StatusSolicitacao.PENDENTE_CONTROLLER);
        }

        return List.of(); // Outros perfis não veem pendências de aprovação
    }

    private void atualizarFinanceiroMonolito(Long osId, BigDecimal valor) {
        if (valor.compareTo(BigDecimal.ZERO) > 0) {
            try {
                String url = monolithUrl + "/api/os/" + osId + "/adicionar-custo-material";
                restTemplate.postForEntity(url, valor, Void.class);
            } catch (Exception e) {
                System.err.println("AVISO: Não foi possível atualizar financeiro da OS " + osId + ": " + e.getMessage());
            }
        }
    }

    public List<SolicitacaoResponseDTO> listarPendentes(String userRole) {
        List<Solicitacao> lista = solicitacaoRepository.findAll();

        return lista.stream()
                .filter(s -> !s.getStatus().name().equals("APROVADO") && !s.getStatus().name().equals("REPROVADO"))
                .map(this::converterParaDTO) // Use o método conversor que criamos na etapa anterior (Turn 4)
                .collect(Collectors.toList());
    }

    public void processarLote(DecisaoLoteDTO dto, String acao, String role) {
        List<Solicitacao> solicitacoes = solicitacaoRepository.findAllById(dto.ids());

        for (Solicitacao s : solicitacoes) {
            if ("APROVAR".equals(acao)) {
                if ("COORDINATOR".equals(role)) {
                    s.setStatus(StatusSolicitacao.PENDENTE_CONTROLLER); // Avança etapa
                } else if ("CONTROLLER".equals(role)) {
                    s.setStatus(StatusSolicitacao.APROVADO); // Finaliza
                }
            } else if ("REJEITAR".equals(acao)) {
                s.setStatus(StatusSolicitacao.REPROVADO);
                // Se tiver campo de observação na entidade, salve:
                // s.setObservacao(dto.observacao());
            }
        }
        solicitacaoRepository.saveAll(solicitacoes);
    }

    private SolicitacaoResponseDTO converterParaDTO(Solicitacao s) {
        OsDTO osDto = null;
        LpuDTO lpuDto = null;
        String nomeSolicitante = "N/A";

        try {
            if (s.getOsId() != null) {
                osDto = restTemplate.getForObject(monolithUrl + "/os/" + s.getOsId(), OsDTO.class);
            }
            if (s.getLpuId() != null) {
                // Ajuste a URL conforme seu controller de LPU no monólito
                lpuDto = restTemplate.getForObject(monolithUrl + "/lpus/" + s.getLpuId(), LpuDTO.class);
            }
            if (s.getSolicitanteId() != null) {
                UsuarioDTO usuario = restTemplate.getForObject(monolithUrl + "/usuarios/" + s.getSolicitanteId(), UsuarioDTO.class);
                if (usuario != null) nomeSolicitante = usuario.nome();
            }
        } catch (Exception e) {
            System.err.println("Erro ao buscar dados externos para solicitacao " + s.getId() + ": " + e.getMessage());
            // Cria objetos vazios para não quebrar o frontend com null pointer
            osDto = new OsDTO(s.getOsId(), "Erro ao carregar", new SegmentoDTO(0L, "-"));
            lpuDto = new LpuDTO(s.getLpuId(), "Erro", "-");
        }

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
}