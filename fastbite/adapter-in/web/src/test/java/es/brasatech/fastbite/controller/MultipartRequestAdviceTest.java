package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.platform.PlatformLink;
import es.brasatech.fastbite.application.tenant.TenantBackupRestorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.util.Locale;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MultipartRequestAdviceTest {
    private MockMvc mvc;
    @BeforeEach void setup() {
        var messages = new StaticMessageSource();
        messages.addMessage("upload.invalidMultipart", Locale.ENGLISH, "Choose a valid upload.");
        messages.addMessage("upload.invalidMultipart", Locale.forLanguageTag("es"), "Selecciona un archivo válido.");
        messages.addMessage("upload.invalidMultipart", Locale.forLanguageTag("pt"), "Selecione um ficheiro válido.");
        var maintenance = new TenantMaintenanceController(mock(TenantBackupRestorePort.class), mock(PlatformLink.class), messages);
        mvc = MockMvcBuilders.standaloneSetup(maintenance, new TestEndpoints()).setControllerAdvice(new BadRequestAdvice(messages)).build();
    }
    @Test void nonMultipartBundleReturnsTranslatedJson() throws Exception {
        mvc.perform(post("/pizza/api/backoffice/maintenance/import-production-bundle").locale(Locale.forLanguageTag("es")))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Selecciona un archivo válido."));
    }
    @Test void missingMultipartFileReturnsTranslatedJson() throws Exception {
        mvc.perform(multipart("/pizza/api/backoffice/maintenance/restore").locale(Locale.forLanguageTag("pt")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Selecione um ficheiro válido."));
    }
    @Test void pageUploadGetsReadableTranslatedError() throws Exception {
        mvc.perform(post("/upload-page").locale(Locale.ENGLISH))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("Choose a valid upload.")));
    }
    @Test void unrelatedMissingParameterKeepsDefaultResponse() throws Exception {
        mvc.perform(post("/ordinary-parameter"))
                .andExpect(status().isBadRequest()).andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResolvedException())
                        .isInstanceOf(org.springframework.web.bind.MissingServletRequestParameterException.class));
    }
    @org.springframework.stereotype.Controller
    static class TestEndpoints {
        @PostMapping("/upload-page") String upload(@RequestParam("file") MultipartFile file) { return "ok"; }
        @PostMapping("/ordinary-parameter") String ordinary(@RequestParam("required") String value) { return "ok"; }
    }
}
