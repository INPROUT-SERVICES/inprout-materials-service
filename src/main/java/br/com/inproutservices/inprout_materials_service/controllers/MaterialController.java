package br.com.inproutservices.inprout_materials_service.controllers;

import br.com.inproutservices.inprout_materials_service.dtos.*;
import br.com.inproutservices.inprout_materials_service.entities.Material;
import br.com.inproutservices.inprout_materials_service.services.MaterialService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/materiais")
@CrossOrigin(originPatterns = "*")
public class MaterialController {

    private final MaterialService materialService;

    public MaterialController(MaterialService materialService) {
        this.materialService = materialService;
    }

    @GetMapping
    public ResponseEntity<List<MaterialResumoDTO>> listarTodosMateriais() {
        return ResponseEntity.ok(materialService.listarTodosResumido());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Material> buscarMaterialPorId(@PathVariable Long id) {
        return ResponseEntity.ok(materialService.buscarPorId(id));
    }

    @PostMapping
    public ResponseEntity<Material> criarMaterial(@RequestBody MaterialRequestDTO dto) {
        Material novoMaterial = materialService.criarMaterial(dto);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(novoMaterial.getId()).toUri();
        return ResponseEntity.created(location).body(novoMaterial);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Material> atualizarMaterial(@PathVariable Long id, @RequestBody MaterialUpdateDTO dto) {
        return ResponseEntity.ok(materialService.atualizarMaterial(id, dto));
    }

    @PostMapping("/entradas")
    public ResponseEntity<Material> adicionarEntrada(@RequestBody EntradaMaterialDTO dto) {
        return ResponseEntity.ok(materialService.adicionarEntrada(dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletarMaterial(@PathVariable Long id) {
        materialService.deletarMaterial(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/importar-legado")
    public ResponseEntity<Map<String, Object>> importarLegadoCMA(@RequestParam("file") MultipartFile file) throws IOException {
        List<String> log = materialService.importarLegadoCMA(file);
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Importação concluída.");
        response.put("log", log);
        return ResponseEntity.ok(response);
    }
}