package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.TestConfig;
import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.verifactu.DeclarationArchive;
import es.brasatech.fastbite.application.verifactu.VerifactuDeclaration;
import es.brasatech.fastbite.application.verifactu.VerifactuSystem;
import es.brasatech.fastbite.config.TenantRoutingResolver;
import es.brasatech.fastbite.config.WebConfig;
import es.brasatech.fastbite.security.SecurityConfig;
import es.brasatech.fastbite.service.ClasspathDeclarationArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DeclarationController.class)
@ContextConfiguration(classes = {TestConfig.class, DeclarationController.class, SecurityConfig.class,
        TenantRoutingResolver.class, WebConfig.class})
class DeclarationEndpointTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private VerifactuDeclaration declaration;
    @MockitoBean private TenantLocationService tenantLocationService;
    @MockitoBean private ClasspathDeclarationArchive archive;

    private static final VerifactuSystem SYSTEM = new VerifactuSystem("Brasatech Digital SL", "B12345674", "FastBite",
            "FB", "1.0", "Servicios de restauración");

    @BeforeEach
    void setUp() {
        when(tenantLocationService.getLocationByCustomDomain(anyString())).thenReturn(Optional.empty());
        when(declaration.system()).thenReturn(SYSTEM);
        when(declaration.onlyVerifactu()).thenReturn(true);
        when(declaration.multipleTaxpayers()).thenReturn(true);
    }

    @Test
    void anyoneCanReadItAndADraftSaysSoAndNamesWhatIsMissing() throws Exception {
        when(declaration.signed()).thenReturn(false);
        when(declaration.producerAddress()).thenReturn("");
        when(declaration.missing()).thenReturn(java.util.List.of("fastbite.verifactu.producer-address"));

        mockMvc.perform(get("/verifactu/declaracion-responsable"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Borrador"),
                        containsString("fastbite.verifactu.producer-address"),
                        containsString("[pendiente]"),
                        containsString("DECLARACIÓN RESPONSABLE DEL SISTEMA INFORMÁTICO DE FACTURACIÓN"),
                        containsString("FastBite"),
                        containsString("B12345674"),
                        containsString("Real Decreto 1007/2023"))));
    }

    @Test
    void aSignedDeclarationShowsNoDraftBanner() throws Exception {
        when(declaration.signed()).thenReturn(true);
        when(declaration.missing()).thenReturn(java.util.List.of());
        when(declaration.producerAddress()).thenReturn("Calle Mayor 1, 28013 Madrid");
        when(declaration.place()).thenReturn("Madrid");
        when(declaration.date()).thenReturn("15 de noviembre de 2026");

        mockMvc.perform(get("/verifactu/declaracion-responsable"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        not(containsString("class=\"draft\"")),
                        containsString("en <span>Madrid</span>"),
                        containsString("l) Fecha y lugar de suscripción"),
                        containsString("Anexo: información adicional"),
                        containsString("15 de noviembre de 2026"),
                        containsString("Calle Mayor 1, 28013 Madrid"))));
    }

    @Test
    void aSignedVersionOffersItsPdfAndTheEarlierOnes() throws Exception {
        var current = new DeclarationArchive.SignedDeclaration("1.1.0", "1 de diciembre de 2026", "Madrid",
                "Ana García, administradora única", "ANA GARCIA", "2026-12-01T10:00:00Z", "def");
        var earlier = new DeclarationArchive.SignedDeclaration("1.0.0", "3 de noviembre de 2026", "Madrid",
                "Ana García, administradora única", "ANA GARCIA", "2026-11-03T10:00:00Z", "abc");
        when(declaration.signed()).thenReturn(true);
        when(declaration.missing()).thenReturn(java.util.List.of());
        when(declaration.signedDocument()).thenReturn(Optional.of(current));
        when(declaration.previous()).thenReturn(java.util.List.of(earlier));
        when(archive.pdf("1.1.0")).thenReturn(Optional.of(new ByteArrayResource("%PDF-1.7 signed".getBytes())));
        when(archive.pdf("9.9.9")).thenReturn(Optional.empty());

        mockMvc.perform(get("/verifactu/declaracion-responsable"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("href=\"/verifactu/declaracion-responsable/FastBite-1.1.0.pdf\""),
                        containsString("Versión 1.0.0, firmada el 3 de noviembre de 2026"))));
        mockMvc.perform(get("/verifactu/declaracion-responsable/FastBite-1.1.0.pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(content().string("%PDF-1.7 signed"));
        mockMvc.perform(get("/verifactu/declaracion-responsable/FastBite-9.9.9.pdf"))
                .andExpect(status().isNotFound());
    }
}
