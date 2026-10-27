package es.brasatech.fastbite.config;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.domain.tenant.TenantLocation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.ConcurrentLruCache;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single place that decides which restaurant (tenant) a request is for.
 * Sources, in order: path prefix ({@code /kebab/menu}), host (subdomain or custom domain),
 * {@code ?tenantId=}, and the Referer's path prefix (AJAX calls from path-prefixed pages).
 * <p>
 * The tenant only selects data; it grants nothing. Access is decided by
 * {@link es.brasatech.fastbite.security.TenantAccessFilter}.
 */
@Component
@Slf4j
public class TenantRoutingResolver {

    /** Request attribute holding the URL prefix for tenant links: "" on tenant hosts, "/{tenantId}" otherwise. */
    public static final String TENANT_URL_PREFIX = "tenantUrlPrefix";

    private static final Pattern TENANT_ID = Pattern.compile("^[a-zA-Z0-9]+$");

    /** Top-level paths that belong to the platform, never to a tenant. */
    private static final Set<String> RESERVED = Set.of(
            "signup", "login", "logout", "owner", "menu", "dashboard", "counter", "backoffice",
            "api", "css", "js", "images", "webjars", "stripe", "error", "actuator", "appspecific", "allergens", "verifactu",
            "internal", "link", "legal", "privacy");

    private static final int CUSTOM_DOMAIN_CACHE_SIZE = 1024;

    private final TenantLocationService tenantLocationService;
    /** Caches custom domain to tenantId lookups; the key is the client-controlled host name (Host or X-Forwarded-Host). */
    private final ConcurrentLruCache<String, String> customDomainCache;
    /** Platform hosts from public-url and link.url that must never resolve as tenant subdomains. */
    private final Set<String> platformHosts;
    /** Restaurants seen to exist, with when they were checked. Unknown ones are never cached: one may be created any moment. */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> knownTenants = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long KNOWN_TENANT_TTL_MS = 5 * 60 * 1000;
    private static final int KNOWN_TENANT_LIMIT = 10_000;

    public TenantRoutingResolver(TenantLocationService tenantLocationService) {
        this(tenantLocationService, null, null);
    }

    @Autowired
    public TenantRoutingResolver(
            TenantLocationService tenantLocationService,
            @Value("${fastbite.public-url:}") String publicUrl,
            @Value("${fastbite.link.url:}") String linkUrl) {
        this.tenantLocationService = tenantLocationService;
        this.customDomainCache = new ConcurrentLruCache<>(CUSTOM_DOMAIN_CACHE_SIZE, this::lookupCustomDomain);
        Set<String> hosts = new HashSet<>();
        addHost(hosts, publicUrl);
        addHost(hosts, linkUrl);
        this.platformHosts = Collections.unmodifiableSet(hosts);
    }

    private static void addHost(Set<String> set, String url) {
        if (url != null && !url.isBlank()) {
            URI uri = URI.create(url.strip());
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                set.add(host.toLowerCase(Locale.ROOT));
            }
        }
    }

    public record ResolvedTenant(String id, boolean fromHost) {
        public String urlPrefix() {
            return fromHost ? "" : "/" + id;
        }
    }

    public ResolvedTenant resolve(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());

        String pathTenant = firstSegment(path);
        if (isTenantSegment(pathTenant)) {
            return new ResolvedTenant(pathTenant, false);
        }

        String hostTenant = resolveTenantId(request.getServerName());
        if (hostTenant != null) {
            return new ResolvedTenant(hostTenant, true);
        }

        // Query string only: form fields named tenantId are data (e.g. the restaurant being created at signup).
        String paramTenant = queryParameter(request, "tenantId");
        if (paramTenant != null && TENANT_ID.matcher(paramTenant).matches()) {
            return new ResolvedTenant(paramTenant, false);
        }

        // Entry pages must not inherit a tenant from wherever the visitor came from.
        if (!isEntryPage(path)) {
            String refererTenant = firstSegment(refererPath(request.getHeader("Referer")));
            if (isTenantSegment(refererTenant)) {
                return new ResolvedTenant(refererTenant, false);
            }
        }
        return null;
    }

    /** Tenant from a Host header: {@code {tenant}.localhost}, {@code {tenant}.fastbite.com} or a bound custom domain. */
    public String resolveTenantId(String host) {
        if (host == null) {
            return null;
        }
        String cleanHost = host.split(":")[0].toLowerCase(Locale.ROOT);

        if (cleanHost.matches("^[0-9.]+$") || platformHosts.contains(cleanHost)) {
            return null;
        }

        if (cleanHost.endsWith(".localhost") || cleanHost.endsWith(".fastbite.com")) {
            String subdomain = cleanHost.substring(0, cleanHost.indexOf('.'));
            return "www".equals(subdomain) || "api".equals(subdomain) ? null : subdomain;
        }

        try {
            String tenantId = customDomainCache.get(cleanHost);
            return tenantId.isEmpty() ? null : tenantId;
        } catch (RuntimeException e) {
            log.error("Failed to resolve tenant by custom domain: {}", cleanHost, e);
            return null;
        }
    }

    public void evictCache(String customDomain) {
        if (customDomain != null) {
            customDomainCache.remove(customDomain.trim().toLowerCase());
        }
    }

    public void evictTenant(String tenantId) {
        if (tenantId != null) {
            knownTenants.remove(tenantId);
        }
    }

    /** True if a restaurant with this id is registered and not closed in this environment. */
    public boolean exists(String tenantId) {
        long now = System.currentTimeMillis();
        Long checked = knownTenants.get(tenantId);
        if (checked != null && now - checked < KNOWN_TENANT_TTL_MS) {
            return true;
        }
        boolean exists = tenantLocationService.getLocation(tenantId).filter(loc -> !loc.isClosed()).isPresent()
                && !tenantLocationService.isClosed(tenantId);
        if (exists) {
            if (knownTenants.size() >= KNOWN_TENANT_LIMIT) knownTenants.clear();
            knownTenants.put(tenantId, now);
        } else {
            knownTenants.remove(tenantId);
        }
        return exists;
    }

    /** True if the path segment is a tenant prefix rather than a platform path. */
    public static boolean isTenantSegment(String segment) {
        return segment != null && TENANT_ID.matcher(segment).matches() && !RESERVED.contains(segment);
    }

    public static String firstSegment(String path) {
        if (path == null || !path.startsWith("/")) {
            return "";
        }
        int end = path.indexOf('/', 1);
        return end < 0 ? path.substring(1) : path.substring(1, end);
    }

    private String lookupCustomDomain(String host) {
        log.info("Resolving custom domain from database: {}", host);
        return tenantLocationService.getLocationByCustomDomain(host)
                .map(TenantLocation::tenantId)
                .orElse("");
    }

    private static boolean isEntryPage(String path) {
        return path.equals("/") || path.startsWith("/login") || path.startsWith("/signup") || path.startsWith("/logout") || path.equals("/set-password");
    }

    private static String queryParameter(HttpServletRequest request, String name) {
        String query = request.getQueryString();
        return query == null ? null : UriComponentsBuilder.newInstance().query(query).build().getQueryParams().getFirst(name);
    }

    private static String refererPath(String referer) {
        if (referer == null) {
            return null;
        }
        try {
            return URI.create(referer).getPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
