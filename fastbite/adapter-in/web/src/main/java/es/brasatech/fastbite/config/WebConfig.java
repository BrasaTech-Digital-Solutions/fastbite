package es.brasatech.fastbite.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.http.CacheControl;
import org.springframework.core.io.Resource;
import org.springframework.util.DigestUtils;
import org.springframework.web.servlet.resource.CssLinkResourceTransformer;
import org.springframework.web.servlet.resource.ResourceUrlEncodingFilter;
import org.springframework.web.servlet.resource.VersionResourceResolver;
import java.io.IOException;
import java.time.Duration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web configuration to serve user-uploaded images.
 */
@Configuration
@ImportRuntimeHints({WebAdapterHints.class, StripeHints.class})
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final TenantRoutingResolver tenantResolver;

    @Value("${image.upload.directory}")
    private String uploadDirectory;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve user-uploaded images from the configured upload directory
        Path uploadPath = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        String uploadLocation = "file:" + uploadPath.toString() + "/";

        registry.addResourceHandler("/user-images/**")
                .addResourceLocations(uploadLocation)
                .setCachePeriod(3600); // Cache for 1 hour

        // VersionResolver checks the full content hash, and the CSS transformer
        // rewrites relative url() references through the same resource chain.
        for (String folder : new String[]{"css", "js", "images"}) {
            registry.addResourceHandler("/" + folder + "/**")
                    .addResourceLocations("classpath:/static/" + folder + "/")
                    .setUseLastModified(false)
                    .setEtagGenerator(WebConfig::contentEtag)
                    .setCacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                    .resourceChain(true)
                    .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"))
                    .addTransformer(new CssLinkResourceTransformer());
        }
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/")
                .setUseLastModified(false)
                .setEtagGenerator(WebConfig::contentEtag)
                .setCacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic());
    }

    private static String contentEtag(Resource resource) {
        try (var input = resource.getInputStream()) {
            return "W/\"" + DigestUtils.md5DigestAsHex(input) + "\"";
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read static resource", e);
        }
    }

    @Bean
    public FilterRegistrationBean<ResourceUrlEncodingFilter> resourceUrlEncodingFilter() {
        var registration = new FilterRegistrationBean<>(new ResourceUrlEncodingFilter());
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<StaticCacheControlFilter> staticCacheControlFilter() {
        var registration = new FilterRegistrationBean<>(new StaticCacheControlFilter());
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ImageCacheControlFilter> imageCacheControlFilter() {
        FilterRegistrationBean<ImageCacheControlFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ImageCacheControlFilter());
        registration.addUrlPatterns("/user-images/*");
        return registration;
    }

    @org.springframework.context.annotation.Bean
    public TenantContextFilter tenantContextFilter() {
        return new TenantContextFilter(tenantResolver);
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<TenantContextFilter> tenantContextFilterRegistration(TenantContextFilter filter) {
        org.springframework.boot.web.servlet.FilterRegistrationBean<TenantContextFilter> registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.addUrlPatterns("/*");
        // Must run before the Spring Security filter chain (-100) so sign-in and access decisions see the tenant.
        registration.setOrder(org.springframework.session.web.http.SessionRepositoryFilter.DEFAULT_ORDER + 10);
        return registration;
    }

    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    public com.fasterxml.jackson.databind.ObjectMapper jacksonObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }
}
