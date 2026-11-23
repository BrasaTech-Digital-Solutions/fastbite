package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.fiscal.*;
import es.brasatech.fastbite.application.order.OrderService;
import es.brasatech.fastbite.application.platform.PlatformEnvironment;
import es.brasatech.fastbite.application.table.TableService;
import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate;
import es.brasatech.fastbite.application.verifactu.VerifactuService;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FiscalMissingBaselineTest {
    @Test void unknownMalformedAndInaccessibleTicketLookupResponses() throws Exception {
        var repository = mock(FiscalTicketRepository.class);
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        var verifactu = mock(VerifactuService.class);
        var service = new FiscalTicketService(mock(OrderService.class), repository, mock(FiscalProfileService.class),
                mock(PlatformEnvironment.class), mock(TableService.class), verifactu,
                mock(VerifactuAuthorizationGate.class), Clock.systemUTC());
        var messages = new StaticMessageSource();
        var mvc = MockMvcBuilders.standaloneSetup(new TicketController(service, verifactu, messages))
                .setControllerAdvice(new BadRequestAdvice(messages)).build();
        var auth = UsernamePasswordAuthenticationToken.authenticated("manager", null, AuthorityUtils.createAuthorityList("ROLE_MANAGER"));
        for (String id : new String[]{UUID.randomUUID().toString(), "not-a-uuid", UUID.randomUUID().toString()}) {
            mvc.perform(get("/counter/tickets/" + id).principal(auth)).andExpect(status().isNotFound());
            mvc.perform(post("/counter/api/tickets/" + id + "/invoice").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"taxId\":\"123\",\"country\":\"ES\",\"name\":\"Guest\",\"address\":\"Street\"}").principal(auth))
                    .andExpect(status().isBadRequest());
            for (String action : new String[]{"refund", "cancel"})
                mvc.perform(post("/counter/api/tickets/" + id + "/" + action).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Test\"}").principal(auth)).andExpect(status().isBadRequest());
        }
    }
}
