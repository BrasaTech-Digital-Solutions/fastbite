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
    es.brasatech.fastbite.application.order.OrderService orders=mock(es.brasatech.fastbite.application.order.OrderService.class);
    es.brasatech.fastbite.application.office.UserService users=mock(es.brasatech.fastbite.application.office.UserService.class);
    es.brasatech.fastbite.application.table.TableService tables=mock(es.brasatech.fastbite.application.table.TableService.class);
    es.brasatech.fastbite.application.table.TableSignatureUtil tableSignatures=new es.brasatech.fastbite.application.table.TableSignatureUtil("test-table-secret");

    OwnerWorkspaceService createService(es.brasatech.fastbite.application.platform.PlatformEnvironment env, es.brasatech.fastbite.application.fiscal.FiscalIssuerPort issuerPort) {
        return new OwnerWorkspaceService(locations, port, products, settings, fiscal, tickets,
                mock(es.brasatech.fastbite.application.verifactu.VerifactuService.class),
                issuerPort, env, java.time.Clock.systemUTC(), orders, users, tables, tableSignatures);
    }

    OwnerWorkspaceService service = createService(new es.brasatech.fastbite.application.platform.PlatformEnvironment("sandbox"), issuers);
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
        assertTrue(new OwnerWorkspaceService.Summary(checked.withOpening(true,false),1,RestaurantSettings.DEFAULTS,COMPLETE,null,false,true,false).ready());
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
        var prodService=createService(new es.brasatech.fastbite.application.platform.PlatformEnvironment("production"), issuers);
        when(locations.isOwnerOf("owner","cafe")).thenReturn(true);

        assertTrue(prodService.saveFiscalProfile("owner","cafe",COMPLETE).isEmpty());
        verify(issuers).recordIfAbsent(eq("12345678Z"),eq("cafe"),any());

        // In sandbox:
        var sandboxIssuers=mock(es.brasatech.fastbite.application.fiscal.FiscalIssuerPort.class);
        var sandboxService=createService(new es.brasatech.fastbite.application.platform.PlatformEnvironment("sandbox"), sandboxIssuers);
        assertTrue(sandboxService.saveFiscalProfile("owner","cafe",COMPLETE).isEmpty());
        verifyNoInteractions(sandboxIssuers);
    }
    @Test void priorNifUsageFindsDateForSameOwner(){
        var issuers=mock(es.brasatech.fastbite.application.fiscal.FiscalIssuerPort.class);
        var instant=java.time.Instant.parse("2026-01-15T10:00:00Z");
        when(locations.isOwnerOf("owner","cafe")).thenReturn(true);
        when(issuers.findPriorUsageByOwner("owner","12345678Z","cafe")).thenReturn(java.util.Optional.of(instant));

        var prodService=createService(new es.brasatech.fastbite.application.platform.PlatformEnvironment("production"), issuers);

        assertEquals(java.util.Optional.of(instant), prodService.priorNifUsage("owner","12345678Z","cafe"));
        assertEquals(java.util.Optional.empty(), prodService.priorNifUsage("owner","invalid-nif","cafe"));
    }
    @Test void practiceTourMilestonesLatchMonotonicallyWhenFactTrueAndStayWhenFactGone() {
        var ordersMock = mock(es.brasatech.fastbite.application.order.OrderService.class);
        var usersMock = mock(es.brasatech.fastbite.application.office.UserService.class);
        var latchService = new OwnerWorkspaceService(locations, port, products, settings, fiscal, tickets,
                mock(es.brasatech.fastbite.application.verifactu.VerifactuService.class),
                issuers, new es.brasatech.fastbite.application.platform.PlatformEnvironment("sandbox"),
                java.time.Clock.systemUTC(), ordersMock, usersMock, tables, tableSignatures);

        when(locations.isOwnerOf("owner", "cafe")).thenReturn(true);
        when(settings.get()).thenReturn(RestaurantSettings.DEFAULTS);
        when(fiscal.get()).thenReturn(FiscalProfile.EMPTY);
        when(products.findAll()).thenReturn(java.util.List.of());

        var progressHolder = new java.util.concurrent.atomic.AtomicReference<>(SetupProgress.EMPTY);
        when(port.read("cafe")).thenAnswer(inv -> progressHolder.get());
        doAnswer(inv -> {
            java.util.function.UnaryOperator<SetupProgress> op = inv.getArgument(1);
            progressHolder.set(op.apply(progressHolder.get()));
            return null;
        }).when(port).update(eq("cafe"), any());

        // Fact 1: Table order is present
        var tableOrder = mock(es.brasatech.fastbite.domain.order.Order.class);
        when(tableOrder.orderChannel()).thenReturn(es.brasatech.fastbite.domain.order.OrderChannel.TABLE);
        when(ordersMock.findAll()).thenReturn(java.util.List.of(tableOrder));

        var summary1 = latchService.summary("owner", "cafe");
        assertTrue(summary1.progress().practiceTableOrdered(), "table order latched when fact true");
        assertFalse(summary1.progress().practiceKitchenPrepared());
        assertFalse(summary1.progress().practiceCounterCharged());
        assertFalse(summary1.progress().practiceTeamTested());

        // Fact 1 gone: Order is cancelled/removed
        when(ordersMock.findAll()).thenReturn(java.util.List.of());

        var summary2 = latchService.summary("owner", "cafe");
        assertTrue(summary2.progress().practiceTableOrdered(), "table order remains latched even after fact is gone");

        // Fact 2: Kitchen prepared order exists
        var kitchenOrder = mock(es.brasatech.fastbite.domain.order.Order.class);
        when(kitchenOrder.status()).thenReturn(es.brasatech.fastbite.domain.order.OrderStatus.DONE);
        when(ordersMock.findAll()).thenReturn(java.util.List.of(kitchenOrder));

        var summary3 = latchService.summary("owner", "cafe");
        assertTrue(summary3.progress().practiceKitchenPrepared(), "kitchen prepared latched when fact true");

        // Fact 2 gone
        when(ordersMock.findAll()).thenReturn(java.util.List.of());
        var summary4 = latchService.summary("owner", "cafe");
        assertTrue(summary4.progress().practiceKitchenPrepared(), "kitchen prepared remains latched when fact gone");

        // Fact 3: Paid counter order exists
        var paidOrder = mock(es.brasatech.fastbite.domain.order.Order.class);
        when(paidOrder.paymentStatus()).thenReturn(es.brasatech.fastbite.domain.order.OrderPaymentStatus.PAID);
        when(ordersMock.findAll()).thenReturn(java.util.List.of(paidOrder));

        var summary5 = latchService.summary("owner", "cafe");
        assertTrue(summary5.progress().practiceCounterCharged(), "counter charged latched when paid order exists");

        // Fact 3 gone
        when(ordersMock.findAll()).thenReturn(java.util.List.of());
        var summary6 = latchService.summary("owner", "cafe");
        assertTrue(summary6.progress().practiceCounterCharged(), "counter charged remains latched when order gone");

        // Fact 4: Non-owner active staff member exists
        var staffMember = mock(es.brasatech.fastbite.domain.user.UserDto.class);
        when(staffMember.isOwner()).thenReturn(false);
        when(staffMember.active()).thenReturn(true);
        when(usersMock.findAll()).thenReturn(java.util.List.of(staffMember));

        var summary7 = latchService.summary("owner", "cafe");
        assertTrue(summary7.progress().practiceTeamTested(), "team tested latched when staff member exists");

        // Fact 4 gone: Staff member removed
        when(usersMock.findAll()).thenReturn(java.util.List.of());
        var summary8 = latchService.summary("owner", "cafe");
        assertTrue(summary8.progress().practiceTeamTested(), "team tested remains latched when staff removed");

        // Manual actions: practice_guest and practice_menu
        latchService.action("owner", "cafe", "practice_guest");
        assertTrue(progressHolder.get().practiceGuestViewed(), "practice_guest action sets practiceGuestViewed");

        latchService.action("owner", "cafe", "practice_menu");
        assertTrue(progressHolder.get().practiceMenuPersonalized(), "practice_menu action sets practiceMenuPersonalized");

        // All 6 milestones complete
        var finalSummary = latchService.summary("owner", "cafe");
        assertEquals(6, finalSummary.progress().practiceCompletedCount());
        assertTrue(finalSummary.progress().practiceReady());
    }

    @Test
    void productionWorkspaceSummaryDoesNotReadOrdersUsersOrTickets() {
        var prodOrders = mock(es.brasatech.fastbite.application.order.OrderService.class);
        var prodUsers = mock(es.brasatech.fastbite.application.office.UserService.class);
        var prodTickets = mock(es.brasatech.fastbite.application.fiscal.FiscalTicketRepository.class);
        var prodTables = mock(es.brasatech.fastbite.application.table.TableService.class);
        var prodService = new OwnerWorkspaceService(locations, port, products, settings, fiscal, prodTickets,
                mock(es.brasatech.fastbite.application.verifactu.VerifactuService.class),
                issuers, new es.brasatech.fastbite.application.platform.PlatformEnvironment("production"),
                java.time.Clock.systemUTC(), prodOrders, prodUsers, prodTables, tableSignatures);

        when(locations.isOwnerOf("owner", "cafe")).thenReturn(true);
        when(port.read("cafe")).thenReturn(SetupProgress.EMPTY);
        when(settings.get()).thenReturn(RestaurantSettings.DEFAULTS);
        when(fiscal.get()).thenReturn(FiscalProfile.EMPTY);
        when(products.findAll()).thenReturn(java.util.List.of());
        when(prodTables.findAll()).thenReturn(java.util.List.of());

        var summary = prodService.summary("owner", "cafe");
        assertNotNull(summary);
        verifyNoInteractions(prodOrders);
        verify(prodUsers).findAll();
        verify(prodTickets).anyRealTicket();
    }
    @Test void openingRequiresTheApplicableStepsAndRemainsLatched() {
        var production = createService(new es.brasatech.fastbite.application.platform.PlatformEnvironment("production"), issuers);
        when(locations.isOwnerOf("owner", "cafe")).thenReturn(true);
        when(settings.get()).thenReturn(new RestaurantSettings(true, false, 0, 0, false));
        when(fiscal.get()).thenReturn(COMPLETE);
        when(products.findAll()).thenReturn(java.util.List.of(mock(es.brasatech.fastbite.domain.product.ProductDto.class)));
        var holder = new java.util.concurrent.atomic.AtomicReference<>(new SetupProgress(true,true,true,false).withOpening(true,false));
        when(port.read("cafe")).thenAnswer(i -> holder.get());
        doAnswer(i -> { java.util.function.UnaryOperator<SetupProgress> update = i.getArgument(1); holder.set(update.apply(holder.get())); return null; }).when(port).update(eq("cafe"), any());
        assertTrue(production.summary("owner", "cafe").opened());
        when(products.findAll()).thenReturn(java.util.List.of());
        var after = production.summary("owner", "cafe");
        assertTrue(after.opened());
        assertFalse(after.menuDone());
        production.action("owner", "cafe", "work_with_team");
        assertFalse(production.summary("owner", "cafe").teamDone());
    }
    @Test void existingRealTicketLatchesOpeningWithoutCompletingChecklist() {
        var production = createService(new es.brasatech.fastbite.application.platform.PlatformEnvironment("production"), issuers);
        when(locations.isOwnerOf("owner", "cafe")).thenReturn(true);
        when(settings.get()).thenReturn(RestaurantSettings.DEFAULTS);
        when(fiscal.get()).thenReturn(FiscalProfile.EMPTY);
        when(tickets.anyRealTicket()).thenReturn(true);
        var holder = new java.util.concurrent.atomic.AtomicReference<>(SetupProgress.EMPTY);
        when(port.read("cafe")).thenAnswer(i -> holder.get());
        doAnswer(i -> { java.util.function.UnaryOperator<SetupProgress> update = i.getArgument(1); holder.set(update.apply(holder.get())); return null; }).when(port).update(eq("cafe"), any());
        assertTrue(production.summary("owner", "cafe").opened());
        assertFalse(production.summary("owner", "cafe").ready());
    }
    @Test void takeawayAddsPaymentStepAndOnlyActiveLocationStaffCounts() {
        var progress = new SetupProgress(true,true,true,false);
        var dineOnly = new RestaurantSettings(true,false,0,0,false);
        var takeaway = new RestaurantSettings(true,true,0,0,false);
        var dine = new OwnerWorkspaceService.Summary(progress,1,dineOnly,COMPLETE,null,true,false,false);
        assertEquals(5,dine.steps());
        assertTrue(dine.ready());
        assertEquals("",dine.nextStep());
        assertEquals("menu",new OwnerWorkspaceService.Summary(progress,0,dineOnly,COMPLETE,null,true,false,false).nextStep());
        assertEquals("fiscal",new OwnerWorkspaceService.Summary(progress,1,dineOnly,FiscalProfile.EMPTY,null,true,false,false).nextStep());
        var awaitingStripe = new OwnerWorkspaceService.Summary(progress,1,takeaway,COMPLETE,null,true,false,false);
        assertEquals(6,awaitingStripe.steps());
        assertFalse(awaitingStripe.ready());
        assertEquals("payments",awaitingStripe.nextStep());
        assertTrue(new OwnerWorkspaceService.Summary(progress,1,takeaway,COMPLETE,null,true,true,false).ready());
    }
    @Test void readingWithStaffDoesNotClearWorkAloneOrWriteProgress() {
        var production = createService(new es.brasatech.fastbite.application.platform.PlatformEnvironment("production"), issuers);
        when(locations.isOwnerOf("owner", "cafe")).thenReturn(true);
        when(settings.get()).thenReturn(RestaurantSettings.DEFAULTS);
        when(fiscal.get()).thenReturn(FiscalProfile.EMPTY);
        var existing = SetupProgress.EMPTY.withOpening(true,true);
        when(port.read("cafe")).thenReturn(existing);
        var member = mock(es.brasatech.fastbite.domain.user.UserDto.class);
        when(member.active()).thenReturn(true);
        when(member.isOwner()).thenReturn(false);
        when(users.findAll()).thenReturn(java.util.List.of(member));
        var result = production.summary("owner", "cafe");
        assertTrue(result.teamDone());
        assertTrue(result.hasStaff());
        assertTrue(result.progress().workAlone());
        verify(port,never()).update(anyString(),any());
    }
}
