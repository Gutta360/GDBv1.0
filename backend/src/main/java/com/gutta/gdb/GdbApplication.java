package com.gutta.gdb;

import com.gutta.gdb.config.GdbProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GdbProperties.class)
public class GdbApplication {
    public static void main(String[] args) {
        SpringApplication.run(GdbApplication.class, args);
    }
}
