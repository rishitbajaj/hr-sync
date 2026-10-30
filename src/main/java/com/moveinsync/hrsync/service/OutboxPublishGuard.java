package com.moveinsync.hrsync.service;

import com.moveinsync.hrsync.domain.ChangeNotice;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class OutboxPublishGuard {

    private volatile Consumer<ChangeNotice> beforePublish = notice -> {
    };

    public void beforePublish(ChangeNotice notice) {
        beforePublish.accept(notice);
    }

    public void setBeforePublish(Consumer<ChangeNotice> beforePublish) {
        this.beforePublish = beforePublish == null ? notice -> {
        } : beforePublish;
    }
}
