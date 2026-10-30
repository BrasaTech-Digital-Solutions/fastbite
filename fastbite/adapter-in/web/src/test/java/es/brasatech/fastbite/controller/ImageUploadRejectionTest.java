package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.storage.FileStorageService;
import es.brasatech.fastbite.domain.exception.ImageUploadRejection;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.IOException;
import java.util.Locale;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ImageUploadRejectionTest {
    @Test void invalidImageReturnsLocalizedJsonWithoutExposingFilename() throws Exception {
        var storage = mock(FileStorageService.class);
        when(storage.uploadImage(any(), anyString(), any())).thenThrow(new ImageUploadRejection("image.upload.invalid", "Invalid image"));
        var messages = new StaticMessageSource();
        messages.addMessage("image.upload.invalid", Locale.forLanguageTag("es"), "Elige una imagen válida.");
        var mvc = MockMvcBuilders.standaloneSetup(new ImageController(storage)).setControllerAdvice(new BadRequestAdvice(messages)).build();
        var file = new MockMultipartFile("file", "private.png", "image/png", "not image".getBytes());
        mvc.perform(multipart("/api/backoffice/images/upload").file(file).locale(Locale.forLanguageTag("es")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Elige una imagen válida."));
    }
    @Test void realIoFailureIsServerErrorAndDoesNotExposeDetails() throws Exception {
        var storage = mock(FileStorageService.class);
        when(storage.uploadImage(any(), anyString(), any())).thenThrow(new IOException("private path"));
        var mvc = MockMvcBuilders.standaloneSetup(new ImageController(storage)).build();
        var file = new MockMultipartFile("file", "x.png", "image/png", new byte[]{1});
        mvc.perform(multipart("/api/backoffice/images/upload").file(file))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").value("Unable to store image."));
    }
}
