package br.com.inproutservices.inprout_materials_service.controllers;

import br.com.inproutservices.inprout_materials_service.dtos.CriarSolicitacaoLoteDTO;
import br.com.inproutservices.inprout_materials_service.dtos.SolicitacaoLoteRequestDTO;
import br.com.inproutservices.inprout_materials_service.dtos.response.SolicitacaoResponseDTO;
import br.com.inproutservices.inprout_materials_service.dtos.DecisaoLoteDTO; // NOVO DTO
import br.com.inproutservices.inprout_materials_service.entities.Solicitacao;
import br.com.inproutservices.inprout_materials_service.services.SolicitacaoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/materiais/solicitacoes")
public class SolicitacaoController {

    private final SolicitacaoService solicitacaoService;

    public SolicitacaoController(SolicitacaoService solicitacaoService) {
        this.solicitacaoService = solicitacaoService;
    }

    // --- CORREÇÃO DO ERRO 404 ---
    @GetMapping("/pendentes")
    public ResponseEntity<List<SolicitacaoResponseDTO>> listarPendentes(
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        List<SolicitacaoResponseDTO> lista = solicitacaoService.listarPendentes(userRole);
        return ResponseEntity.ok(lista);
    }

    // --- NOVOS ENDPOINTS PARA APROVAÇÃO EM LOTE ---
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

    // --- MÉTODOS ANTIGOS ---
    @PostMapping("/lote")
    public ResponseEntity<List<Solicitacao>> criarLote(@RequestBody CriarSolicitacaoLoteDTO dto) {
        List<Solicitacao> novasSolicitacoes = solicitacaoService.criarSolicitacaoEmLote(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(novasSolicitacoes);
    }

}