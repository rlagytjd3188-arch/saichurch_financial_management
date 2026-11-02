package org.church.steward.finance;

import org.church.steward.domain.financialrecord.service.impl.RecordAccountingEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AccountingListener {
    private final EntityManager entities;
    private final FinanceEngine engine;

    @EventListener
    public void changed(RecordAccountingEvent event) {
        entities.flush();
        engine.synchronize(event.recordId(), event.created(), event.cancelled());
    }
}
