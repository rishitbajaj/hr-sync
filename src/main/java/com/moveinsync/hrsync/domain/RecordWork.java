package com.moveinsync.hrsync.domain;

import java.util.function.Supplier;

public interface RecordWork {

    <T> T call(Supplier<T> work);
}
