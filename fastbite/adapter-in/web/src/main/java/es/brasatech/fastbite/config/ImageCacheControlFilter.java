package es.brasatech.fastbite.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.regex.Pattern;

/** Upload names identify their content; a hand-modified backup is a known exception. */
public class ImageCacheControlFilter extends OncePerRequestFilter {
    private static final Pattern HASHED_IMAGE = Pattern.compile("(?:^|/)[0-9a-f]{32}\\.(?:webp|jpg|png)$");
    private static final String IMMUTABLE = "public, max-age=31536000, immutable";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ((!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod()))
                || !path.startsWith("/user-images/") || !HASHED_IMAGE.matcher(path).find()) {
            chain.doFilter(request, response);
            return;
        }
        // Rewrite only the resource handler's one-hour directive; leave security no-store headers intact.
        var wrapper = new HttpServletResponseWrapper(response) {
            @Override public void setHeader(String name, String value) {
                super.setHeader(name, "Cache-Control".equalsIgnoreCase(name) && "max-age=3600".equals(value) ? IMMUTABLE : value);
            }
            @Override public void addHeader(String name, String value) {
                super.addHeader(name, "Cache-Control".equalsIgnoreCase(name) && "max-age=3600".equals(value) ? IMMUTABLE : value);
            }
        };
        chain.doFilter(request, wrapper);
    }
}
