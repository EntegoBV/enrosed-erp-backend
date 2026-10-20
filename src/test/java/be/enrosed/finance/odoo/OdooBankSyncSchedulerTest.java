package be.enrosed.finance.odoo;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.mockito.Mockito.*;

class OdooBankSyncSchedulerTest {
    @Test void unconfiguredOrDisabledIntegrationMakesNoAttempt() {
        var scheduler=new OdooBankSyncScheduler();scheduler.service=mock(OdooService.class);scheduler.ticaCreditnotes=mock(OdooTicaCreditnoteImportService.class);
        scheduler.lincCosts=mock(OdooLincCostMatchService.class);
        when(scheduler.ticaCreditnotes.syncIfDue()).thenReturn(Optional.empty());
        when(scheduler.service.automaticBankSyncReady()).thenReturn(false);
        scheduler.tick();verify(scheduler.service,never()).syncBankIfDue();verify(scheduler.ticaCreditnotes,never()).syncIfDue();verify(scheduler.lincCosts,never()).syncIfDue();
    }
    @Test void enabledTickDelegatesToPersistedDueCheckAndSafelyHandlesProviderFailures() {
        var scheduler=new OdooBankSyncScheduler();scheduler.service=mock(OdooService.class);scheduler.ticaCreditnotes=mock(OdooTicaCreditnoteImportService.class);
        scheduler.lincCosts=mock(OdooLincCostMatchService.class);
        when(scheduler.lincCosts.syncIfDue()).thenReturn(Optional.empty());
        when(scheduler.ticaCreditnotes.syncIfDue()).thenReturn(Optional.empty());
        when(scheduler.service.automaticBankSyncReady()).thenReturn(true);
        when(scheduler.service.syncBankIfDue()).thenReturn(Optional.empty()).thenThrow(new OdooFailure("BUSY"))
                .thenThrow(new OdooFailure("UNAVAILABLE"));
        scheduler.tick();scheduler.tick();scheduler.tick();
        verify(scheduler.service,times(3)).syncBankIfDue();
        verify(scheduler.service,never()).syncBank();
        verify(scheduler.ticaCreditnotes,times(3)).syncIfDue();
        verify(scheduler.lincCosts,times(3)).syncIfDue();
    }
    @Test void statementFailureDoesNotPreventLincMatching() {
        var scheduler=new OdooBankSyncScheduler();scheduler.service=mock(OdooService.class);
        scheduler.ticaCreditnotes=mock(OdooTicaCreditnoteImportService.class);scheduler.lincCosts=mock(OdooLincCostMatchService.class);
        when(scheduler.service.automaticBankSyncReady()).thenReturn(true);
        when(scheduler.service.syncBankIfDue()).thenReturn(Optional.empty());
        when(scheduler.ticaCreditnotes.syncIfDue()).thenThrow(new OdooFailure("UNAVAILABLE"));
        when(scheduler.lincCosts.syncIfDue()).thenReturn(Optional.empty());
        scheduler.tick();verify(scheduler.lincCosts).syncIfDue();
    }
}
