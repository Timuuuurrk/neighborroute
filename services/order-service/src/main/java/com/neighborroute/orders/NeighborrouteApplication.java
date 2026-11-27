package com.neighborroute.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class NeighborrouteApplication {

    public static void main(String[] args) {
        SpringApplication.run(NeighborrouteApplication.class, args);
    }

}
