package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.domain.group.Group;
import es.brasatech.fastbite.domain.product.ProductDto;
import es.brasatech.fastbite.domain.table.Table;
import es.brasatech.fastbite.dto.office.BackOfficeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only menu and tables for the counter, open to every counter role. The back-office endpoints with the same
 * data are for admins and managers only, because they also edit it.
 */
@RestController
@RequiredArgsConstructor
public class CounterCatalogController {

    private final ProductService productService;
    private final GroupService groupService;
    private final CustomizationService customizationService;
    private final TableService tableService;

    @GetMapping("/counter/api/catalog/products")
    public List<BackOfficeDto<ProductDto>> products() {
        return productService.findAll().stream().map(product -> BackOfficeDto.of(product.id(), product)).toList();
    }

    @GetMapping("/counter/api/catalog/groups")
    public List<BackOfficeDto<Group>> groups() {
        return groupService.findAll().stream().map(group -> BackOfficeDto.of(group.id(), group)).toList();
    }

    @GetMapping("/counter/api/catalog/customizations")
    public List<BackOfficeDto<CustomizationDto>> customizations() {
        return customizationService.findAll().stream()
                .filter(c -> c.options() != null && !c.options().isEmpty())
                .map(customization -> BackOfficeDto.of(customization.id(), customization)).toList();
    }

    @GetMapping("/counter/api/catalog/tables")
    public List<BackOfficeDto<Table>> tables() {
        return tableService.findAll().stream().map(table -> BackOfficeDto.of(table.id(), table)).toList();
    }
}
