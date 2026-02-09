package br.com.inproutservices.inprout_materials_service;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.util.TimeZone;

@SpringBootApplication
public class InproutMaterialApplication {

    public static void main(String[] args) {
        SpringApplication.run(InproutMaterialApplication.class, args);
    }

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"));
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}