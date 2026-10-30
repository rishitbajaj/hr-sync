package com.moveinsync.hrsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HrSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(HrSyncApplication.class, args);
    }
}
