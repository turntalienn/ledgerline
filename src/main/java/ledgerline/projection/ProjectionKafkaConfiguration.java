package ledgerline.projection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(
    name = "ledgerline.outbox.consumers.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ProjectionKafkaConfiguration {
  @Bean
  ConcurrentKafkaListenerContainerFactory<Object, Object> projectionKafkaListenerContainerFactory(
      ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
      ConsumerFactory<Object, Object> consumerFactory) {
    var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
    configurer.configure(factory, consumerFactory);
    // Spring's default handler eventually skips a poison record. Skipping a money delta
    // would permanently corrupt this read model. Keep retrying instead; an operator must
    // repair the payload/code and replay it. Partition lag is the operational signal.
    var errors = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    errors.setClassifications(
        java.util.Map.of(), true); // even malformed events must not be skipped
    factory.setCommonErrorHandler(errors);
    return factory;
  }
}
