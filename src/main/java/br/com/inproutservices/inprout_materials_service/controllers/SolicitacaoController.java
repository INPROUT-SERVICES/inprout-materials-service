package br.com.inproutservices.inprout_materials_service.controllers;

import br.com.inproutservices.inprout_materials_service.dtos.CriarSolicitacaoLoteDTO;
import br.com.inproutservices.inprout_materials_service.dtos.DecisaoItemDTO;
import br.com.inproutservices.inprout_materials_service.dtos.DecisaoLoteDTO;
import br.com.inproutservices.inprout_materials_service.dtos.response.SolicitacaoResponseDTO;
import br.com.inproutservices.inprout_materials_service.entities.Solicitacao;
import br.com.inproutservices.inprout_materials_service.services.SolicitacaoService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/materiais/solicitacoes")
@CrossOrigin(originPatterns = "*")
public class SolicitacaoController {

    private final SolicitacaoService service;

    public SolicitacaoController(SolicitacaoService service) {
        this.service = service;
    }

    @PostMapping("/lote")
    public ResponseEntity<List<Solicitacao>> criarSolicitacaoLote(@RequestBody CriarSolicitacaoLoteDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.criarSolicitacaoEmLote(dto));
    }

    // Endpoint de pendências já recebe os Headers, mantido.
    @GetMapping("/pendentes")
    public ResponseEntity<List<SolicitacaoResponseDTO>> listarPendentes(
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-User-Id", required = false) Long userId
    ) {
        List<SolicitacaoResponseDTO> lista = service.listarPendentes(role, userId);
        return ResponseEntity.ok(lista);
    }

    // --- ATUALIZAÇÃO: HISTÓRICO COM SEGURANÇA ---
    @GetMapping("/historico")
    public ResponseEntity<List<SolicitacaoResponseDTO>> listarHistorico(
            @RequestParam(value = "inicio", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate inicio,
            @RequestParam(value = "fim", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fim,
            @RequestHeader(value = "X-User-Role", required = false) String role,
            @RequestHeader(value = "X-User-Id", required = false) Long userId
    ) {
        // Passamos a role e o userId para o service filtrar o segmento
        List<SolicitacaoResponseDTO> lista = service.listarHistorico(inicio, fim, role, userId);
        return ResponseEntity.ok(lista);
    }

    @PostMapping("/{id}/itens/{itemId}/decidir")
    public ResponseEntity<Void> decidirItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestBody DecisaoItemDTO dto,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        service.decidirItem(itemId, dto.acao(), dto.observacao(), role);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/coordenador/aprovar-lote")
    public ResponseEntity<Void> aprovarLoteCoordenador(
            @RequestBody DecisaoLoteDTO dto,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        service.processarLote(dto, "APROVAR", role);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/coordenador/rejeitar-lote")
    public ResponseEntity<Void> rejeitarLoteCoordenador(
            @RequestBody DecisaoLoteDTO dto,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        service.processarLote(dto, "REJEITAR", role);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/controller/aprovar-lote")
    public ResponseEntity<Void> aprovarLoteController(
            @RequestBody DecisaoLoteDTO dto,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        service.processarLote(dto, "APROVAR", role);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/controller/rejeitar-lote")
    public ResponseEntity<Void> rejeitarLoteController(
            @RequestBody DecisaoLoteDTO dto,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        service.processarLote(dto, "REJEITAR", role);
        return ResponseEntity.ok().build();
    }
}