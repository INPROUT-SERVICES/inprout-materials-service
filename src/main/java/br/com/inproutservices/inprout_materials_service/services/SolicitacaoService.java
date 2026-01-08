package br.com.inproutservices.inprout_materials_service.services;

import br.com.inproutservices.inprout_materials_service.dtos.CriarSolicitacaoLoteDTO;
import br.com.inproutservices.inprout_materials_service.entities.ItemSolicitacao;
import br.com.inproutservices.inprout_materials_service.entities.Material;
import br.com.inproutservices.inprout_materials_service.entities.Solicitacao;
import br.com.inproutservices.inprout_materials_service.enums.StatusSolicitacao;
import br.com.inproutservices.inprout_materials_service.repositories.MaterialRepository;
import br.com.inproutservices.inprout_materials_service.repositories.SolicitacaoRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class SolicitacaoService {

    private final SolicitacaoRepository solicitacaoRepository;
    private final MaterialRepository materialRepository;
    private final RestTemplate restTemplate;

    @Value("${app.monolith.url}")
    private String monolithUrl;

    public SolicitacaoService(SolicitacaoRepository solicitacaoRepository,
                              MaterialRepository materialRepository,
                              RestTemplate restTemplate) {
        this.solicitacaoRepository = solicitacaoRepository;
        this.materialRepository = materialRepository;
        this.restTemplate = restTemplate;
    }

    @Transactional
    public List<Solicitacao> criarSolicitacaoEmLote(CriarSolicitacaoLoteDTO dto) {
        List<Solicitacao> criadas = new ArrayList<>();

        for (CriarSolicitacaoLoteDTO.ItemLoteRequest itemDto : dto.itens()) {
            // 1. Validar Material
            Material material = materialRepository.findById(itemDto.materialId())
                    .orElseThrow(() -> new EntityNotFoundException("Material não encontrado ID: " + itemDto.materialId()));

            // 2. Criar Solicitação
            Solicitacao solicitacao = new Solicitacao();
            solicitacao.setOsId(itemDto.osId());
            solicitacao.setLpuId(itemDto.lpuId());
            solicitacao.setSolicitanteId(dto.idSolicitante());
            solicitacao.setJustificativa(dto.justificativa());
            solicitacao.setStatus(StatusSolicitacao.PENDENTE_COORDENADOR);

            // 3. Adicionar Item
            ItemSolicitacao item = new ItemSolicitacao(solicitacao, material, itemDto.quantidade());
            solicitacao.getItens().add(item);

            solicitacaoRepository.save(solicitacao);
            criadas.add(solicitacao);

            // 4. Comunicar com Monólito (Atualizar Financeiro da OS)
            // Calculamos o custo total desse item
            BigDecimal custoTotal = material.getCustoMedioPonderado().multiply(itemDto.quantidade());

            if (custoTotal.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    String url = monolithUrl + "/api/os/" + itemDto.osId() + "/adicionar-custo-material";
                    restTemplate.postForEntity(url, custoTotal, Void.class);
                } catch (Exception e) {
                    // Logar erro, mas talvez não quebrar a transação se o financeiro puder ser reconciliado depois
                    System.err.println("Erro ao atualizar financeiro no monólito: " + e.getMessage());
                }
            }
        }
        return criadas;
    }
}