package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.domain.exception.ResourceNotFoundException;
import es.brasatech.fastbite.application.office.CustomizationService;
import es.brasatech.fastbite.domain.customization.CustomizationDto;
import es.brasatech.fastbite.dto.office.BackOfficeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/api/backoffice/customizations")
@RequiredArgsConstructor
public class CustomizationController {

    private final CustomizationService customizationService;

    // ===== CUSTOMIZATION ENDPOINTS =====

    /**
     * Get all customizations
     */
    @GetMapping
    @ResponseBody
    public ResponseEntity<List<BackOfficeDto<CustomizationDto>>> getAllCustomizations() {
        List<CustomizationDto> customizations = customizationService.findAll();
        List<BackOfficeDto<CustomizationDto>> response = customizations.stream()
                .map(customization -> BackOfficeDto.of(customization.id(), customization))
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Get customization by ID
     */
    @GetMapping("/{id}")
    @ResponseBody
    public ResponseEntity<BackOfficeDto<CustomizationDto>> getCustomizationById(@PathVariable String id) {
        return customizationService.findById(id)
                .map(customization -> BackOfficeDto.of(customization.id(), customization))
                .map(ResponseEntity::ok)
                .orElseThrow(ResourceNotFoundException::new);
    }

    /**
     * Create a new customization
     */
    @PostMapping
    @ResponseBody
    public ResponseEntity<BackOfficeDto<CustomizationDto>> createCustomization(
            @RequestBody CustomizationDto customizationDto) {
        CustomizationDto created = customizationService.create(customizationDto);
        BackOfficeDto<CustomizationDto> response = BackOfficeDto.of(created.id(), created);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Update an existing customization
     */
    @PutMapping("/{id}")
    @ResponseBody
    public ResponseEntity<BackOfficeDto<CustomizationDto>> updateCustomization(
            @PathVariable String id,
            @RequestBody CustomizationDto customizationDto) {
        // Check the tenant-scoped ID before validating changes to prevent leaking another location's item.
        customizationService.findById(id).orElseThrow(ResourceNotFoundException::new);
        return customizationService.update(id, customizationDto)
                .map(customization -> BackOfficeDto.of(customization.id(), customization))
                .map(ResponseEntity::ok)
                .orElseThrow(ResourceNotFoundException::new);
    }

    /**
     * Delete a customization
     */
    @DeleteMapping("/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteCustomization(@PathVariable String id) {
        if (customizationService.delete(id)) {
            return ResponseEntity.ok().build();
        }
        throw new ResourceNotFoundException();
    }
}
