package es.brasatech.fastbite.application.tenant;
import es.brasatech.fastbite.application.fiscal.FiscalProfileService;
import es.brasatech.fastbite.application.office.ProductService;
import es.brasatech.fastbite.application.settings.RestaurantSettingsService;
import es.brasatech.fastbite.domain.fiscal.FiscalProfile;
import es.brasatech.fastbite.domain.settings.RestaurantSettings;
import java.math.BigDecimal;
import es.brasatech.fastbite.domain.tenant.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OwnerWorkspaceServiceTest {
    TenantLocationService locations=mock(TenantLocationService.class);
    OwnerWorkspacePort port=mock(OwnerWorkspacePort.class);
    ProductService products=mock(ProductService.class);
    RestaurantSettingsService settings=mock(RestaurantSettingsService.class);
    FiscalProfileService fiscal=mock(FiscalProfileService.class);
    es.brasatech.fastbite.application.fiscal.FiscalTicketRepository tickets=mock(es.brasatech.fastbite.application.fiscal.FiscalTicketRepository.class);
    es.brasatech.fastbite.application.fiscal.FiscalIssuerPort issuers=mock(es.brasatech.fastbite.application.fiscal.FiscalIssuerPort.class);
    OwnerWorkspaceService service=new OwnerWorkspaceService(locations,port,products,settings,fiscal,tickets,
            mock(es.brasatech.fastbite.application.verifactu.VerifactuService.class),
            issuers, new es.brasatech.fastbite.application.platform.PlatformEnvironment("sandbox"), java.time.Clock.systemUTC());
    static final FiscalProfile COMPLETE=new FiscalProfile("Ana García","Kebab Ana","12345678Z","Calle Mayor 1","28013","Madrid","Madrid",null,null,"KA",new BigDecimal("10"));
    @AfterEach void clear(){TenantContext.clear();}
    @Test void deniesForeignLocationBeforeReadingOrWriting(){
        assertThrows(IllegalArgumentException.class,()->service.summary("other","cafe"));
        assertThrows(IllegalArgumentException.class,()->service.action("other","cafe","dismiss"));
        assertThrows(IllegalArgumentException.class,()->service.saveService("other","cafe",true,true,0,0));
        assertThrows(IllegalArgumentException.class,()->service.saveFiscalProfile("other","cafe",COMPLETE));
        verifyNoInteractions(port,products,settings,fiscal);
    }
    @Test void restoresContextEvenWhenTenantReadFails(){
        when(locations.isOwnerOf("owner","cafe")).thenReturn(true);
        TenantContext.setCurrentTenant("original");when(port.read("cafe")).thenReturn(SetupProgress.EMPTY);
        when(products.findAll()).thenAnswer(inv->{assertEquals("cafe",TenantContext.getCurrentTenant());throw new IllegalStateException();});
        assertThrows(IllegalStateException.class,()->service.summary("owner","cafe"));assertEquals("original",TenantContext.getCurrentTenant());
    }
    @Test void unsuccessfulSettingsDoNotAdvanceProgress(){
        when(locations.isOwnerOf("owner","cafe")).thenReturn(true);
        assertThrows(IllegalArgumentException.class,()->service.saveService("owner","cafe",false,false,0,0));
        assertThrows(IllegalArgumentException.class,()->service.saveService("owner","cafe",true,true,10,5));
        when(settings.save(any())).thenThrow(new IllegalStateException());
        assertThrows(IllegalStateException.class,()->service.saveService("owner","cafe",true,false,5,10));
        verifyNoInteractions(port);assertNull(TenantContext.getCurrentTenant());
    }
    @Test void menuReadinessUsesActualProducts(){
        var checked=new SetupProgress(true,true,true,false);
        assertEquals(3,new OwnerWorkspaceService.Summary(checked,0,RestaurantSettings.DEFAULTS,COMPLETE).completed());
        assertFalse(new OwnerWorkspaceService.Summary(checked,0,RestaurantSettings.DEFAULTS,COMPLETE).ready());
        assertTrue(new OwnerWorkspaceService.Summary(checked,1,RestaurantSettings.DEFAULTS,COMPLETE).ready());
    }
    @Test void aLocationIsNotReadyUntilItsBusinessDetailsAreComplete(){
        var checked=new SetupProgress(true,true,true,false);
        assertFalse(new OwnerWorkspaceService.Summary(checked,1,RestaurantSettings.DEFAULTS,FiscalProfile.EMPTY).ready());
    }
    @Test void businessDetailsCanBeSavedHalfDoneButNeverWrong(){
        when(locations.isOwnerOf("owner","cafe")).thenReturn(true);
        var draft=new FiscalProfile("Ana García",null,null,null,"28013",null,null,null,null,null,null);
        assertTrue(service.saveFiscalProfile("owner","cafe",draft).isEmpty());
        verify(fiscal).save(draft);
        var wrong=new FiscalProfile("Ana García",null,"12345678A",null,null,null,null,null,null,null,null);
        assertEquals("fiscal.error.taxId",service.saveFiscalProfile("owner","cafe",wrong).get("taxId"));
        verify(fiscal,never()).save(wrong);
        assertNull(TenantContext.getCurrentTenant());
    }
    @Test void afterTheFirstTicketTheNifAndSeriesStayAndTheDetailsStayComplete(){
        when(locations.isOwnerOf("owner","cafe")).thenReturn(true);
        when(tickets.anyRealTicket()).thenReturn(true);
        when(fiscal.get()).thenReturn(COMPLETE);
        var otherNif=new FiscalProfile("Ana García","Kebab Ana","B12345674","Calle Mayor 1","28013","Madrid","Madrid",null,null,"XX",new BigDecimal("10"));
        var problems=service.saveFiscalProfile("owner","cafe",otherNif);
        assertEquals("fiscal.error.taxId.locked",problems.get("taxId"));
        assertEquals("fiscal.error.seriesPrefix.locked",problems.get("seriesPrefix"));
        var halfDone=new FiscalProfile("Ana García",null,"12345678Z",null,"28013",null,null,null,null,"KA",null);
        assertEquals("fiscal.error.required",service.saveFiscalProfile("owner","cafe",halfDone).get("addressLine"));
        var moved=new FiscalProfile("Ana García","Kebab Ana","12345678Z","Calle Nueva 5","28013","Madrid","Madrid",null,null,"KA",new BigDecimal("10"));
        assertTrue(service.saveFiscalProfile("owner","cafe",moved).isEmpty());
        verify(fiscal).save(moved);
        verify(fiscal,never()).save(otherNif);
    }
    @Test void recordsValidNifInProductionOnly(){
        var issuers=mock(es.brasatech.fastbite.application.fiscal.FiscalIssuerPort.class);
        var prodService=new OwnerWorkspaceService(locations,port,products,settings,fiscal,tickets,
                mock(es.brasatech.fastbite.application.verifactu.VerifactuService.class),
                issuers, new es.brasatech.fastbite.application.platform.PlatformEnvironment("production"), java.time.Clock.systemUTC());
        when(locations.isOwnerOf("owner","cafe")).thenReturn(true);

        assertTrue(prodService.saveFiscalProfile("owner","cafe",COMPLETE).isEmpty());
        verify(issuers).recordIfAbsent(eq("12345678Z"),eq("cafe"),any());

        // In sandbox:
        var sandboxIssuers=mock(es.brasatech.fastbite.application.fiscal.FiscalIssuerPort.class);
        var sandboxService=new OwnerWorkspaceService(locations,port,products,settings,fiscal,tickets,
                mock(es.brasatech.fastbite.application.verifactu.VerifactuService.class),
                sandboxIssuers, new es.brasatech.fastbite.application.platform.PlatformEnvironment("sandbox"), java.time.Clock.systemUTC());
        assertTrue(sandboxService.saveFiscalProfile("owner","cafe",COMPLETE).isEmpty());
        verifyNoInteractions(sandboxIssuers);
    }
    @Test void priorNifUsageFindsDateForSameOwner(){
        var issuers=mock(es.brasatech.fastbite.application.fiscal.FiscalIssuerPort.class);
        var instant=java.time.Instant.parse("2026-01-15T10:00:00Z");
        when(locations.isOwnerOf("owner","cafe")).thenReturn(true);
        when(issuers.findPriorUsageByOwner("owner","12345678Z","cafe")).thenReturn(java.util.Optional.of(instant));

        var prodService=new OwnerWorkspaceService(locations,port,products,settings,fiscal,tickets,
                mock(es.brasatech.fastbite.application.verifactu.VerifactuService.class),
                issuers, new es.brasatech.fastbite.application.platform.PlatformEnvironment("production"), java.time.Clock.systemUTC());

        assertEquals(java.util.Optional.of(instant), prodService.priorNifUsage("owner","12345678Z","cafe"));
        assertEquals(java.util.Optional.empty(), prodService.priorNifUsage("owner","invalid-nif","cafe"));
    }
}
