package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.payment.PaymentService;
import es.brasatech.fastbite.domain.payment.PaymentConfig;
import es.brasatech.fastbite.domain.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/api/backoffice/payment")
@RequiredArgsConstructor
public class BackOfficePaymentController {

    private final PaymentService paymentService;

    @GetMapping
    @ResponseBody
    public PaymentConfig getActiveConfig() {
        return paymentService.getActiveConfig();
    }

    @GetMapping("/all")
    @ResponseBody
    public List<PaymentConfig> getAllConfigs() {
        return paymentService.findAllConfigs();
    }

    @PostMapping
    @ResponseBody
    public void saveConfig(@RequestBody PaymentConfig config) {
        paymentService.saveConfig(config);
    }

    @PostMapping("/{id}/active")
    @ResponseBody
    public void setActive(@PathVariable String id) {
        paymentService.findConfigById(id).orElseThrow(ResourceNotFoundException::new);
        paymentService.setActiveConfig(id);
    }

    @DeleteMapping("/{id}")
    @ResponseBody
    public void deleteConfig(@PathVariable String id) {
        paymentService.findConfigById(id).orElseThrow(ResourceNotFoundException::new);
        paymentService.deleteConfig(id);
    }
}
