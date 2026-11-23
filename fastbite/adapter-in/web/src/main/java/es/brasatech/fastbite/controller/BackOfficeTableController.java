package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.dto.office.BackOfficeDto;
import es.brasatech.fastbite.domain.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/api/backoffice/tables")
@RequiredArgsConstructor
public class BackOfficeTableController {

    private final TableService tableService;

    @GetMapping
    @ResponseBody
    public ResponseEntity<List<BackOfficeDto<Table>>> listTables() {
        List<Table> tables = tableService.findAll();
        List<BackOfficeDto<Table>> response = tables.stream()
                .map(table -> BackOfficeDto.of(table.id(), table))
                .toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @ResponseBody
    public BackOfficeDto<Table> createTable(@RequestBody Table table) {
        Table created = tableService.create(table);
        return BackOfficeDto.of(created.id(), created);
    }

    @PutMapping("/{id}")
    @ResponseBody
    public BackOfficeDto<Table> updateTable(@PathVariable String id, @RequestBody Table table) {
        Table updated = tableService.update(id, table).orElseThrow(ResourceNotFoundException::new);
        return BackOfficeDto.of(updated.id(), updated);
    }

    @DeleteMapping("/{id}")
    @ResponseBody
    public void deleteTable(@PathVariable String id) {
        if (!tableService.delete(id)) {
            throw new ResourceNotFoundException();
        }
    }
}
