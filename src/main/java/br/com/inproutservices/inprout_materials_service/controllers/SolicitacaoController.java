package br.com.inproutservices.inprout_materials_service.controllers;

import br.com.inproutservices.inprout_materials_service.dtos.CriarSolicitacaoLoteDTO;
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

    @PostMapping("/lote")
    public ResponseEntity<List<Solicitacao>> criarLote(@RequestBody CriarSolicitacaoLoteDTO dto) {
        List<Solicitacao> novasSolicitacoes = solicitacaoService.criarSolicitacaoEmLote(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(novasSolicitacoes);
    }
}