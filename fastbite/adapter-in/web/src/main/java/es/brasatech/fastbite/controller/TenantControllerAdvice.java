package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.domain.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class TenantControllerAdvice {

    /**
     * The restaurant of this request. Error pages are rendered after TenantContextFilter has cleared the
     * TenantContext, so they read the request attribute the filter also sets.
     */
    @ModelAttribute("tenantId")
    public String getTenantId(HttpServletRequest request) {
        String tenant = TenantContext.getCurrentTenant();
        if (tenant != null) return tenant;
        return request.getAttribute("tenantId") instanceof String attribute ? attribute : null;
    }
}
