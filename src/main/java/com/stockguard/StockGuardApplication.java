package com.stockguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StockGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockGuardApplication.class, args);
    }
}
