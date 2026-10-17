package es.brasatech.fastbite.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OrderLiveEventServiceTest {

    @Test
    void closeAllEmittersCompletesAllEmittersAndClearsRegistrations() {
        var service = new OrderLiveEventService();

        SseEmitter tenantEmitter = service.subscribeTenant("cafe");
        SseEmitter orderEmitter = service.subscribeOrder("order-123");

        assertThat(service.activeTenantEmittersCount()).isEqualTo(1);
        assertThat(service.activeOrderEmittersCount()).isEqualTo(1);

        service.closeAllEmitters();

        assertThat(service.activeTenantEmittersCount()).isZero();
        assertThat(service.activeOrderEmittersCount()).isZero();
    }

    @Test
    void onContextClosedCompletesAllEmitters() {
        var service = new OrderLiveEventService();

        service.subscribeTenant("bistro");
        service.subscribeOrder("order-456");

        assertThat(service.activeTenantEmittersCount()).isEqualTo(1);
        assertThat(service.activeOrderEmittersCount()).isEqualTo(1);

        service.onContextClosed(mock(ContextClosedEvent.class));

        assertThat(service.activeTenantEmittersCount()).isZero();
        assertThat(service.activeOrderEmittersCount()).isZero();
    }
}
