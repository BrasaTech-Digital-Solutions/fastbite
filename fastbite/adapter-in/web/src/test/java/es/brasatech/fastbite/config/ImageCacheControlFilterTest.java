package es.brasatech.fastbite.config;

import es.brasatech.fastbite.TestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ImageCacheControlFilterTest.DummyController.class)
@ContextConfiguration(classes = {TestConfig.class, WebConfig.class, ImageCacheControlFilterTest.DummyController.class})
class ImageCacheControlFilterTest {
    private static final Path ROOT = tempDirectory();
    private static final String HASH = "0123456789abcdef0123456789abcdef.png";
    @DynamicPropertySource static void config(DynamicPropertyRegistry properties) {
        properties.add("image.upload.directory", () -> ROOT.toString());
    }
    @MockitoBean TenantRoutingResolver resolver;
    @Autowired WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach void setUp() throws Exception {
        Path tenant = Files.createDirectories(ROOT.resolve("tenant"));
        Files.write(tenant.resolve(HASH), new byte[]{1, 2, 3});
        Files.write(tenant.resolve("old-photo.png"), new byte[]{1, 2, 3});
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new ImageCacheControlFilter()).build();
    }
    @Test void contentAddressedImageHasImmutableCacheAndLegacyKeepsOneHour() throws Exception {
        mvc.perform(get("/user-images/tenant/" + HASH)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"));
        mvc.perform(head("/user-images/tenant/" + HASH)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"));
        mvc.perform(get("/user-images/tenant/old-photo.png")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=3600"));
    }
    @Test void missingHashNameDoesNotAcquireLongCache() throws Exception {
        mvc.perform(get("/user-images/tenant/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png"))
                .andExpect(status().isNotFound()).andExpect(header().doesNotExist("Cache-Control"));
    }
    @Test void securityNoStoreIsNotChanged() throws Exception {
        var request = new MockHttpServletRequest("GET", "/user-images/tenant/" + HASH);
        var response = new MockHttpServletResponse();
        new ImageCacheControlFilter().doFilter(request, response, (req, res) -> {
            var http = (HttpServletResponse) res;
            http.setStatus(403);
            http.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
        });
        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
    }
    @RestController static class DummyController { @GetMapping("/nothing") String nothing() { return ""; } }
    private static Path tempDirectory() {
        try { return Files.createTempDirectory("fastbite-cache-test-"); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @AfterAll static void removeTemp() throws Exception {
        try (var files = Files.walk(ROOT)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
