package es.brasatech.fastbite.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/** Only verified hashed resources and explicit release-version WebJars get a long cache. */
public class StaticCacheControlFilter extends OncePerRequestFilter {
    private static final Pattern HASHED = Pattern.compile("^/(?:css|js|images)/.+-[0-9a-f]{32}\\.[^/]+$");
    private static final Pattern VERSIONED_WEBJAR = Pattern.compile("^/webjars/[^/]+/[0-9]+(?:\\.[0-9]+){1,3}/.+$");
    private static final String SHORT_CACHE = "max-age=600, public";
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ((!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod()))
                || !(HASHED.matcher(path).matches() || VERSIONED_WEBJAR.matcher(path).matches())) {
            chain.doFilter(request, response);
            return;
        }
        // ResourceHttpRequestHandler writes this exact directive only after resolving the file.
        // Preserve security no-store and error responses, including a forged hash.
        var wrapped = new HttpServletResponseWrapper(response) {
            private String replace(String name, String value) {
                return "Cache-Control".equalsIgnoreCase(name) && SHORT_CACHE.equals(value) ? IMMUTABLE : value;
            }
            @Override public void setHeader(String name, String value) { super.setHeader(name, replace(name, value)); }
            @Override public void addHeader(String name, String value) { super.addHeader(name, replace(name, value)); }
        };
        chain.doFilter(request, wrapped);
    }
}
