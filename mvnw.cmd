package br.com.inproutservices.inproutmaterial;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class InproutMaterialApplication {

    public static void main(String[] args) {
        SpringApplication.run(InproutMaterialApplication.class, args);
    }

    // Configuração necessária para comunicarmos com o Monólito
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}