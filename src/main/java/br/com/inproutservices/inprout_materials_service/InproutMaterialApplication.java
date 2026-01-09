package br.com.inproutservices.inprout_materials_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean; // Importação necessária
import org.springframework.web.client.RestTemplate; // Importação necessária

@SpringBootApplication
public class InproutMaterialApplication {

    public static void main(String[] args) {
        SpringApplication.run(InproutMaterialApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}