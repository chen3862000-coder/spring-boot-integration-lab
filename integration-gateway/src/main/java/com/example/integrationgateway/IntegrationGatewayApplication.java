package com.example.integrationgateway;

import com.example.integrationgateway.config.PartnerClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PartnerClientProperties.class)
public class IntegrationGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntegrationGatewayApplication.class, args);
    }
}
