package br.com.inproutservices.inprout_materials_service.controllers;

import br.com.inproutservices.inprout_materials_service.dtos.CriarSolicitacaoLoteDTO;
import br.com.inproutservices.inprout_materials_service.dtos.DecisaoLoteDTO;
import br.com.inproutservices.inprout_materials_service.dtos.response.SolicitacaoResponseDTO;
import br.com.inproutservices.inprout_materials_service.entities.Solicitacao;
import br.com.inproutservices.inprout_materials_service.services.SolicitacaoService;
import br.com.inproutservices.inprout_materials_service.services.dtos.DecisaoItemDTO;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/materiais/solicitacoes")
@CrossOrigin(originPatterns = "*") // permite chamadas do front (ex.: localhost:8080 -> localhost:8081)
public class SolicitacaoController {

    private final SolicitacaoService solicitacaoService;

    public SolicitacaoController(SolicitacaoService solicitacaoService) {
        this.solicitacaoService = solicitacaoService;
    }

    /**
     * Lista pendências para o papel informado em X-User-Role.
     * Mantém compatibilidade com "/pendentes" e também aceita "/pendencias" (PT-BR) para evitar 404 por typo.
     */
    @GetMapping({"/pendentes", "/pendencias"})
    public ResponseEntity<List<SolicitacaoResponseDTO>> listarPendentes(
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        List<SolicitacaoResponseDTO> lista = solicitacaoService.listarPendentes(userRole);
        return ResponseEntity.ok(lista);
    }

    @GetMapping("/historico")
    public ResponseEntity<List<SolicitacaoResponseDTO>> listarHistorico(
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        List<SolicitacaoResponseDTO> lista = solicitacaoService.listarHistorico(userRole, userId);
        return ResponseEntity.ok(lista);
    }

    // --- ENDPOINTS PARA APROVAÇÃO EM LOTE ---
    @PostMapping("/coordenador/aprovar-lote")
    public ResponseEntity<Void> aprovarLoteCoordenador(@RequestBody DecisaoLoteDTO dto) {
        solicitacaoService.processarLote(dto, "APROVAR", "COORDINATOR");
        return ResponseEntity.ok().build();
    }

    @PostMapping("/coordenador/rejeitar-lote")
    public ResponseEntity<Void> rejeitarLoteCoordenador(@RequestBody DecisaoLoteDTO dto) {
        solicitacaoService.processarLote(dto, "REJEITAR", "COORDINATOR");
        return ResponseEntity.ok().build();
    }

    @PostMapping("/controller/aprovar-lote")
    public ResponseEntity<Void> aprovarLoteController(@RequestBody DecisaoLoteDTO dto) {
        solicitacaoService.processarLote(dto, "APROVAR", "CONTROLLER");
        return ResponseEntity.ok().build();
    }

    @PostMapping("/controller/rejeitar-lote")
    public ResponseEntity<Void> rejeitarLoteController(@RequestBody DecisaoLoteDTO dto) {
        solicitacaoService.processarLote(dto, "REJEITAR", "CONTROLLER");
        return ResponseEntity.ok().build();
    }

    // --- MÉTODOS ANTIGOS / COMPATIBILIDADE ---
    @PostMapping("/lote")
    public ResponseEntity<List<Solicitacao>> criarLote(@RequestBody CriarSolicitacaoLoteDTO dto) {
        List<Solicitacao> novasSolicitacoes = solicitacaoService.criarSolicitacaoEmLote(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(novasSolicitacoes);
    }

    @PostMapping("/{idSolicitacao}/itens/{idItem}/decidir")
    public ResponseEntity<Void> decidirItem(
            @PathVariable Long idSolicitacao,
            @PathVariable Long idItem,
            @RequestBody @Valid DecisaoItemDTO dto,
            @RequestHeader(value = "X-User-Role", required = false) String role) {

        solicitacaoService.decidirItem(
                idItem,
                dto.acao(),          // "APROVAR" ou "REJEITAR"
                dto.observacao(),    // Motivo (obrigatório se for REJEITAR)
                role
        );

        return ResponseEntity.ok().build();
    }
}
