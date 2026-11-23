package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.discount.DiscountService;
import es.brasatech.fastbite.domain.discount.DiscountRule;
import es.brasatech.fastbite.dto.office.BackOfficeDto;
import es.brasatech.fastbite.domain.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/api/backoffice/discounts")
@RequiredArgsConstructor
public class BackOfficeDiscountController {

    private final DiscountService discountService;

    @GetMapping
    @ResponseBody
    public ResponseEntity<List<BackOfficeDto<DiscountRule>>> listDiscounts() {
        List<DiscountRule> list = discountService.findAll();
        List<BackOfficeDto<DiscountRule>> response = list.stream()
                .map(rule -> BackOfficeDto.of(rule.id(), rule))
                .toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @ResponseBody
    public BackOfficeDto<DiscountRule> createDiscount(@RequestBody DiscountRule rule) {
        DiscountRule created = discountService.create(rule);
        return BackOfficeDto.of(created.id(), created);
    }

    @PutMapping("/{id}")
    @ResponseBody
    public BackOfficeDto<DiscountRule> updateDiscount(@PathVariable String id, @RequestBody DiscountRule rule) {
        DiscountRule updated = discountService.update(id, rule).orElseThrow(ResourceNotFoundException::new);
        return BackOfficeDto.of(updated.id(), updated);
    }

    @DeleteMapping("/{id}")
    @ResponseBody
    public void deleteDiscount(@PathVariable String id) {
        if (!discountService.delete(id)) {
            throw new ResourceNotFoundException();
        }
    }
}
