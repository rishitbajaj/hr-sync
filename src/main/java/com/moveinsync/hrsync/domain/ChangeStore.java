package com.moveinsync.hrsync.domain;

public interface ChangeStore {

    IngestResult apply(ChangeCommand command);
}
