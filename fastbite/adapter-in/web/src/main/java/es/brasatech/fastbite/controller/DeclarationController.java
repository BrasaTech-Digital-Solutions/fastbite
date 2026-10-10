package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.verifactu.VerifactuDeclaration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The producer's declaración responsable, readable by anyone from the software itself: restaurants,
 * their advisers and tax inspectors.
 */
@Controller
@RequiredArgsConstructor
public class DeclarationController {

    private final VerifactuDeclaration declaration;

    @GetMapping("/verifactu/declaracion-responsable")
    public String declaration(Model model) {
        model.addAttribute("declaration", declaration);
        model.addAttribute("system", declaration.system());
        return "fastfood/declaration";
    }
}
