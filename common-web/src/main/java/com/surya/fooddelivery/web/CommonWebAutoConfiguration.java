package com.surya.fooddelivery.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration picked up by every service that depends on common-web.
 *
 * <p>Registered in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * rather than component-scanned, because this package sits outside each service's scan root.
 */
@AutoConfiguration
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration {

    /** Titles each service's Swagger page with its own application name. */
    @Bean
    @ConditionalOnMissingBean
    public OpenAPI foodDeliveryOpenAPI(@Value("${spring.application.name:service}") String applicationName) {
        return new OpenAPI().info(new Info()
                .title(applicationName + " API")
                .version("1.0.0")
                .description("Food Delivery App — " + applicationName));
    }
}
