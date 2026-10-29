package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.domain.RecordWork;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
public class RequiresNewRecordWork implements RecordWork {

    private final TransactionTemplate transactions;

    public RequiresNewRecordWork(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T call(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
