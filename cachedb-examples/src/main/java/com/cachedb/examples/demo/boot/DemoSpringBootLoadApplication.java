package com.reactor.cachedb.examples.demo.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.reactor.cachedb")
public class DemoSpringBootLoadApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoSpringBootLoadApplication.class, args);
    }
}
