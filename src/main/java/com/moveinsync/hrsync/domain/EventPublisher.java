package com.moveinsync.hrsync.domain;

import java.util.ArrayList;
import java.util.List;

public interface EventPublisher {

    void publish(ChangeNotice notice);

    /**
     * Sends every notice before waiting for the next round. One employee's notices are never
     * passed together: the caller supplies a single row per employee.
     */
    default List<PublishResult> publishRound(List<ChangeNotice> notices) {
        List<PublishResult> results = new ArrayList<>(notices.size());
        for (ChangeNotice notice : notices) {
            try {
                publish(notice);
                results.add(new PublishResult(notice, null));
            } catch (RuntimeException exception) {
                results.add(new PublishResult(notice, exception));
            }
        }
        return results;
    }
}
