package com.surya.fooddelivery.security.feign;

import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires identity propagation for services that call each other synchronously.
 *
 * <p>Only applies where Feign is actually on the classpath, so services that never make outbound
 * calls stay unaffected.
 */
@AutoConfiguration
@ConditionalOnClass(RequestInterceptor.class)
public class FeignTokenRelayAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BearerTokenRelayInterceptor bearerTokenRelayInterceptor() {
        return new BearerTokenRelayInterceptor();
    }

    /**
     * Keeps the security context alive across the circuit breaker's thread hop.
     *
     * <p>This is the subtle part. Resilience4j runs the guarded call on a <em>separate</em> thread
     * so its time limiter can abandon a call that hangs — you cannot time out work happening on the
     * thread that is doing the waiting. But {@code SecurityContextHolder} stores the authenticated
     * principal in a {@link ThreadLocal}, so the moment the call moves to a pool thread the identity
     * vanishes, {@link BearerTokenRelayInterceptor} finds nothing, and the downstream service
     * answers 401. The symptom is maddening to debug: authentication works right up until circuit
     * breakers are switched on.
     *
     * <p>Spring Security's {@link DelegatingSecurityContextExecutorService} is the fix — it captures
     * the context at submit time and installs it on the worker thread, then clears it afterwards,
     * which matters on a pooled thread that will serve a different user next.
     *
     * <p>The lazier fix is {@code spring.cloud.circuitbreaker.resilience4j.disable-threadpool=true},
     * keeping the call on the request thread. That works, but silently gives up the timeout — the
     * protection the circuit breaker was added for in the first place.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Resilience4JCircuitBreakerFactory.class)
    public static class CircuitBreakerContextPropagation {

        @Bean
        @ConditionalOnMissingBean
        public Customizer<Resilience4JCircuitBreakerFactory> securityContextPropagatingExecutor() {
            ExecutorService delegate = Executors.newCachedThreadPool();
            return factory -> factory.configureExecutorService(
                    new DelegatingSecurityContextExecutorService(delegate));
        }
    }
}
