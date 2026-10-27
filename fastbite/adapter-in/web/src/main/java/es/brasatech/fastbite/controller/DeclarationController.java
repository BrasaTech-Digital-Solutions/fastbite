package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.verifactu.VerifactuDeclaration;
import es.brasatech.fastbite.service.ClasspathDeclarationArchive;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/**
 * The producer's declaración responsable, readable by anyone from the software itself: restaurants,
 * their advisers and tax inspectors. Each version's signed PDF can be downloaded, the earlier ones too,
 * because older invoices were issued under them.
 */
@Controller
public class DeclarationController {

    private final VerifactuDeclaration declaration;
    private final ClasspathDeclarationArchive archive;
    private final String producerEmail;
    private final String producerWebsite;

    /** The email and website complete the declaration's annex (Orden HAC/1177/2024, art. 15.2). */
    public DeclarationController(VerifactuDeclaration declaration, ClasspathDeclarationArchive archive,
            @Value("${fastbite.legal.email:info@brasatech.es}") String producerEmail,
            @Value("${fastbite.legal.website:https://fastbite.brasatech.es}") String producerWebsite) {
        this.declaration = declaration;
        this.archive = archive;
        this.producerEmail = producerEmail;
        this.producerWebsite = producerWebsite;
    }

    @GetMapping("/verifactu/declaracion-responsable")
    public String declaration(Model model) {
        model.addAttribute("declaration", declaration);
        model.addAttribute("system", declaration.system());
        model.addAttribute("producerEmail", producerEmail);
        model.addAttribute("producerWebsite", producerWebsite);
        model.addAttribute("signedPdf", declaration.signedDocument()
                .map(signed -> "/verifactu/declaracion-responsable/" + signed.fileName()).orElse(null));
        model.addAttribute("previousDeclarations", declaration.previous());
        return "fastfood/declaration";
    }

    @GetMapping("/verifactu/declaracion-responsable/FastBite-{version}.pdf")
    public ResponseEntity<Resource> signedPdf(@PathVariable String version) {
        Resource pdf = archive.pdf(version).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("FastBite-" + version + ".pdf").build().toString())
                .body(pdf);
    }
}
