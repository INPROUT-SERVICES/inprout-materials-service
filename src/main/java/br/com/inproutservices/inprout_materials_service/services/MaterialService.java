package br.com.inproutservices.inprout_materials_service.services;

import br.com.inproutservices.inprout_materials_service.dtos.*;
import br.com.inproutservices.inprout_materials_service.entities.EntradaMaterial;
import br.com.inproutservices.inprout_materials_service.entities.Material;
import br.com.inproutservices.inprout_materials_service.repositories.MaterialRepository;
// Se o SolicitacaoRepository não existir ainda, comente as linhas referentes a validação de delete
// import br.com.inproutservices.inprout_materials_service.repositories.SolicitacaoRepository;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException; // Usando exceção padrão do Spring para simplificar
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class MaterialService {

    private final MaterialRepository materialRepository;
    // private final SolicitacaoRepository solicitacaoRepository; // Habilitar quando tiver repository de solicitação

    public MaterialService(MaterialRepository materialRepository) {
        this.materialRepository = materialRepository;
    }

    @Transactional(readOnly = true)
    public List<Material> listarTodos() {
        return materialRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Material buscarPorId(Long id) {
        return materialRepository.findByIdWithEntradas(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Material não encontrado com o ID: " + id));
    }

    @Transactional
    public Material criarMaterial(MaterialRequestDTO dto) {
        materialRepository.findByCodigo(dto.codigo()).ifPresent(m -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O código '" + dto.codigo() + "' já está em uso.");
        });

        Material material = new Material();
        material.setCodigo(dto.codigo());
        material.setDescricao(dto.descricao());
        material.setModelo(dto.modelo());
        material.setNumeroDeSerie(dto.numeroDeSerie());
        material.setUnidadeMedida(dto.unidadeMedida());
        material.setSaldoFisico(dto.saldoFisicoInicial());
        material.setObservacoes(dto.observacoes());
        material.setEmpresa(dto.empresa());

        // A primeira entrada define o custo médio inicial
        material.setCustoMedioPonderado(dto.custoUnitarioInicial());

        // Cria a primeira entrada no histórico
        EntradaMaterial primeiraEntrada = new EntradaMaterial();
        primeiraEntrada.setMaterial(material);
        primeiraEntrada.setQuantidade(dto.saldoFisicoInicial());
        primeiraEntrada.setCustoUnitario(dto.custoUnitarioInicial());
        primeiraEntrada.setObservacoes("Entrada inicial de estoque.");
        material.getEntradas().add(primeiraEntrada);

        return materialRepository.save(material);
    }

    @Transactional
    public Material atualizarMaterial(Long id, MaterialUpdateDTO dto) {
        Material material = buscarPorId(id);

        if (dto.codigo() != null && !dto.codigo().equals(material.getCodigo())) {
            materialRepository.findByCodigo(dto.codigo()).ifPresent(existente -> {
                if (!existente.getId().equals(material.getId())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O código '" + dto.codigo() + "' já está em uso por outro material.");
                }
            });
            material.setCodigo(dto.codigo());
        }

        material.setDescricao(dto.descricao());
        material.setObservacoes(dto.observacoes());
        material.setModelo(dto.modelo());
        material.setNumeroDeSerie(dto.numeroDeSerie());

        if (dto.saldoFisico() != null) {
            material.setSaldoFisico(dto.saldoFisico());
        }

        return materialRepository.save(material);
    }

    @Transactional
    public Material adicionarEntrada(EntradaMaterialDTO dto) {
        Material material = buscarPorId(dto.materialId());

        BigDecimal saldoAtual = material.getSaldoFisico();
        BigDecimal custoMedioAtual = material.getCustoMedioPonderado() != null ? material.getCustoMedioPonderado() : BigDecimal.ZERO;
        BigDecimal novaQuantidade = dto.quantidade();
        BigDecimal novoCustoUnitario = dto.custoUnitario();

        BigDecimal valorEstoqueAtual = saldoAtual.multiply(custoMedioAtual);
        BigDecimal valorNovaEntrada = novaQuantidade.multiply(novoCustoUnitario);
        BigDecimal novoSaldo = saldoAtual.add(novaQuantidade);

        if (novoSaldo.compareTo(BigDecimal.ZERO) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O novo saldo não pode ser zero.");
        }

        BigDecimal novoCustoMedio = (valorEstoqueAtual.add(valorNovaEntrada)).divide(novoSaldo, 4, RoundingMode.HALF_UP);

        material.setSaldoFisico(novoSaldo);
        material.setCustoMedioPonderado(novoCustoMedio);

        EntradaMaterial novaEntrada = new EntradaMaterial();
        novaEntrada.setMaterial(material);
        novaEntrada.setQuantidade(dto.quantidade());
        novaEntrada.setCustoUnitario(dto.custoUnitario());
        novaEntrada.setObservacoes(dto.observacoes());
        material.getEntradas().add(novaEntrada);

        return materialRepository.save(material);
    }

    @Transactional
    public void deletarMaterial(Long id) {
        Material material = buscarPorId(id);
        // Lógica de validação de solicitação (reabilitar quando tiver SolicitacaoRepository)
        /* if (solicitacaoRepository.existsByItensMaterialId(id)) {
             throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Não é possível deletar pois já foi utilizado em solicitações.");
        }
        */
        materialRepository.deleteById(id);
    }

    @Transactional
    public List<String> importarLegadoCMA(MultipartFile file) throws IOException {
        List<String> log = new ArrayList<>();
        List<String> unidadesValidas = Arrays.asList("PÇ", "MT", "LT");
        List<String> empresasValidas = Arrays.asList("INPROUT", "CLIENTE");

        try (InputStream inputStream = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(inputStream)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();

            if (rows.hasNext()) rows.next(); // Pula cabeçalho

            int rowCounter = 1;
            while (rows.hasNext()) {
                Row currentRow = rows.next();
                rowCounter++;

                if (isRowEmpty(currentRow)) continue;

                try {
                    String empresa = getStringCellValue(currentRow, 0);
                    String codigo = getStringCellValue(currentRow, 1);
                    String descricao = getStringCellValue(currentRow, 2);
                    String modelo = getStringCellValue(currentRow, 3);
                    String numeroDeSerie = getStringCellValue(currentRow, 4);
                    String unidade = getStringCellValue(currentRow, 5);
                    BigDecimal saldoFisico = getBigDecimalCellValue(currentRow, 6);
                    BigDecimal custoUnitario = getBigDecimalCellValue(currentRow, 7);

                    String codigoOriginal = codigo;
                    if ("PENDENTE".equalsIgnoreCase(codigo)) {
                        codigo = String.format("PENDENTE-%04d", rowCounter);
                    }

                    if (saldoFisico == null) saldoFisico = BigDecimal.ZERO;
                    if (custoUnitario == null) custoUnitario = BigDecimal.ZERO;

                    if (codigo == null || descricao == null || unidade == null || empresa == null) {
                        log.add("Linha " + rowCounter + ": ERRO - Campos obrigatórios em branco.");
                        continue;
                    }

                    if (materialRepository.findByCodigo(codigo).isPresent()) {
                        log.add("Linha " + rowCounter + ": IGNORADO - Código '" + codigo + "' já existe.");
                        continue;
                    }

                    MaterialRequestDTO dto = new MaterialRequestDTO(
                            codigo, descricao, modelo, numeroDeSerie, unidade.toUpperCase(),
                            saldoFisico, custoUnitario, "Importação Legado CMA", empresa.toUpperCase()
                    );
                    criarMaterial(dto);
                    log.add("Linha " + rowCounter + ": SUCESSO - Material criado.");

                } catch (Exception e) {
                    log.add("Linha " + rowCounter + ": ERRO - " + e.getMessage());
                }
            }
        }
        return log;
    }

    // Métodos auxiliares privados (isRowEmpty, getStringCellValue, etc) permanecem iguais ao seu arquivo original
    // Copie-os do arquivo que você enviou
    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (int cellNum = row.getFirstCellNum(); cellNum < row.getLastCellNum(); cellNum++) {
            Cell cell = row.getCell(cellNum, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null && cell.getCellType() != CellType.BLANK) return false;
        }
        return true;
    }

    private String getStringCellValue(Row row, int cellIndex) {
        Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().trim();
        if (cell.getCellType() == CellType.NUMERIC) return new DataFormatter().formatCellValue(cell).trim();
        return null;
    }

    private BigDecimal getBigDecimalCellValue(Row row, int cellIndex) {
        Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) return BigDecimal.valueOf(cell.getNumericCellValue());
        if (cell.getCellType() == CellType.STRING) {
            String value = cell.getStringCellValue().trim();
            if ("-".equals(value)) return BigDecimal.ZERO;
            try { return new BigDecimal(value.replace(",", ".")); } catch (Exception e) { return null; }
        }
        return null;
    }
}