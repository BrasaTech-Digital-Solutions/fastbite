package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.verifactu.VerifactuDeclaration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The producer's declaración responsable, readable by anyone from the software itself: restaurants,
 * their advisers and tax inspectors.
 */
@Controller
public class DeclarationController {

    private final VerifactuDeclaration declaration;
    private final String producerEmail;
    private final String producerWebsite;

    /** The email and website complete the declaration's annex (Orden HAC/1177/2024, art. 15.2). */
    public DeclarationController(VerifactuDeclaration declaration,
            @Value("${fastbite.legal.email:info@brasatech.es}") String producerEmail,
            @Value("${fastbite.legal.website:https://fastbite.brasatech.es}") String producerWebsite) {
        this.declaration = declaration;
        this.producerEmail = producerEmail;
        this.producerWebsite = producerWebsite;
    }

    @GetMapping("/verifactu/declaracion-responsable")
    public String declaration(Model model) {
        model.addAttribute("declaration", declaration);
        model.addAttribute("system", declaration.system());
        model.addAttribute("producerEmail", producerEmail);
        model.addAttribute("producerWebsite", producerWebsite);
        return "fastfood/declaration";
    }
}
