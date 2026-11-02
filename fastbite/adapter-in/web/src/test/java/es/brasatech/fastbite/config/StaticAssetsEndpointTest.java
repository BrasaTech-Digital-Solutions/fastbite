package es.brasatech.fastbite.config;

import es.brasatech.fastbite.TestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = StaticAssetsEndpointTest.Page.class, properties = "image.upload.directory=target/static-cache-uploads")
@ContextConfiguration(classes = {TestConfig.class, WebConfig.class, StaticAssetsEndpointTest.Page.class})
class StaticAssetsEndpointTest {
    @MockitoBean TenantRoutingResolver resolver;
    @Autowired WebApplicationContext context;
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new ResourceUrlEncodingFilter(), new StaticCacheControlFilter()).build();
    }
    @Controller static class Page { @GetMapping("/asset-page") String page() { return "test-asset-page"; } }

    @Test void thymeleafAndCssEmitVerifiedContentUrlsWithImmutableCache() throws Exception {
        String page = mvc.perform(get("/asset-page")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var css = Pattern.compile("/css/fonts-[0-9a-f]{32}\\.css").matcher(page);
        var js = Pattern.compile("/js/platform-[0-9a-f]{32}\\.js").matcher(page);
        assertThat(css.find()).as(page).isTrue();
        assertThat(js.find()).as(page).isTrue();
        String cssBody = mvc.perform(get(css.group())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("immutable"))).andReturn().getResponse().getContentAsString();
        mvc.perform(get(js.group())).andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("immutable")));
        var font = Pattern.compile("/css/fonts/[a-z0-9-]+-[0-9a-f]{32}\\.woff2").matcher(cssBody);
        assertThat(font.find()).as(cssBody).isTrue();
        mvc.perform(get(font.group())).andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("immutable")));
        mvc.perform(get("/css/fonts-00000000000000000000000000000000.css")).andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Cache-Control"));
    }

    @Test void unversionedFilesHaveShortStableEtagsAndOnlyReleaseWebjarsAreLongLived() throws Exception {
        var plain = mvc.perform(get("/css/fonts.css")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=600, public"))
                .andExpect(header().doesNotExist("Last-Modified")).andReturn().getResponse();
        String tag = plain.getHeader("ETag");
        assertThat(tag).isNotBlank();
        mvc.perform(get("/css/fonts.css").header("If-None-Match", tag)).andExpect(status().isNotModified());
        mvc.perform(get("/webjars/bootstrap/5.3.0/css/bootstrap.min.css")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("immutable")));
        mvc.perform(get("/webjars/bootstrap/5.3.1/css/bootstrap.min.css")).andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Cache-Control"));
    }
}
