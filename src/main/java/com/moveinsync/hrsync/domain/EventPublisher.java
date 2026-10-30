package com.moveinsync.hrsync.domain;

public interface EventPublisher {

    void publish(ChangeNotice notice);
}
