package es.brasatech.fastbite.controller;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResourceNotFoundAdviceTest {
    private final ResourceNotFoundAdvice advice = new ResourceNotFoundAdvice();

    @Test
    void apiReturnsGenericJsonWithoutResourceDetails() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/counter/api/orders/secret");
        Object result = advice.notFound(request, new MockHttpServletResponse());
        ResponseEntity<?> response = (ResponseEntity<?>) result;
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(Map.of("message", "Resource not found"), response.getBody());
    }

    @Test
    void htmlDelegatesToExistingErrorPage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertNull(advice.notFound(new MockHttpServletRequest("GET", "/owner/workspace/unknown"), response));
        assertEquals(404, response.getStatus());
        assertEquals(true, response.isCommitted());
    }
}
