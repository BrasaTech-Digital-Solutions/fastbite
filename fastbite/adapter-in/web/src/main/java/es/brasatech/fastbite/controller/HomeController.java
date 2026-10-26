package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.verifactu.VerifactuDeclaration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final VerifactuDeclaration declaration;

    @GetMapping("/")
    public String home(Principal principal, Model model) {
        String currentTenant = es.brasatech.fastbite.domain.tenant.TenantContext.getCurrentTenant();
        if (currentTenant != null) {
            return "redirect:/menu";
        }
        if (principal != null) {
            model.addAttribute("ownerUsername", principal.getName());
        }
        model.addAttribute("declaration", declaration);
        return "fastfood/index";
    }
}
