/*
 * Copyright 2012-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.actuate.autoconfigure.tracing;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.micrometer.tracing.SpanCustomizer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelSpanCustomizer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.otel.bridge.OtelTracer.EventPublisher;
import io.micrometer.tracing.otel.bridge.Slf4JBaggageEventListener;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.micrometer.tracing.otel.propagation.BaggageTextMapPropagator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link OpenTelemetryAutoConfiguration}.
 *
 * @author Moritz Halbritter
 * @author Andy Wilkinson
 */
class OpenTelemetryAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OpenTelemetryAutoConfiguration.class));

	@Test
	void shouldSupplyBeans() {
		this.contextRunner.run((context) -> {
			assertThat(context).hasSingleBean(OtelTracer.class);
			assertThat(context).hasSingleBean(EventPublisher.class);
			assertThat(context).hasSingleBean(OtelCurrentTraceContext.class);
			assertThat(context).hasSingleBean(OpenTelemetry.class);
			assertThat(context).hasSingleBean(SdkTracerProvider.class);
			assertThat(context).hasSingleBean(ContextPropagators.class);
			assertThat(context).hasSingleBean(Sampler.class);
			assertThat(context).hasSingleBean(Tracer.class);
			assertThat(context).hasSingleBean(Slf4JEventListener.class);
			assertThat(context).hasSingleBean(Slf4JBaggageEventListener.class);
			assertThat(context).hasSingleBean(SpanProcessor.class);
			assertThat(context).hasSingleBean(OtelPropagator.class);
			assertThat(context).hasSingleBean(TextMapPropagator.class);
			assertThat(context).hasSingleBean(OtelSpanCustomizer.class);
		});
	}

	@Test
	void samplerIsParentBased() {
		this.contextRunner.run((context) -> {
			Sampler sampler = context.getBean(Sampler.class);
			assertThat(sampler).isNotNull();
			assertThat(sampler.getDescription()).startsWith("ParentBased{");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "io.micrometer.tracing.otel", "io.opentelemetry.sdk", "io.opentelemetry.api" })
	void shouldNotSupplyBeansIfDependencyIsMissing(String packageName) {
		this.contextRunner.withClassLoader(new FilteredClassLoader(packageName)).run((context) -> {
			assertThat(context).doesNotHaveBean(OtelTracer.class);
			assertThat(context).doesNotHaveBean(EventPublisher.class);
			assertThat(context).doesNotHaveBean(OtelCurrentTraceContext.class);
			assertThat(context).doesNotHaveBean(OpenTelemetry.class);
			assertThat(context).doesNotHaveBean(SdkTracerProvider.class);
			assertThat(context).doesNotHaveBean(ContextPropagators.class);
			assertThat(context).doesNotHaveBean(Sampler.class);
			assertThat(context).doesNotHaveBean(Tracer.class);
			assertThat(context).doesNotHaveBean(Slf4JEventListener.class);
			assertThat(context).doesNotHaveBean(Slf4JBaggageEventListener.class);
			assertThat(context).doesNotHaveBean(SpanProcessor.class);
			assertThat(context).doesNotHaveBean(OtelPropagator.class);
			assertThat(context).doesNotHaveBean(TextMapPropagator.class);
			assertThat(context).doesNotHaveBean(OtelSpanCustomizer.class);
		});
	}

	@Test
	void shouldBackOffOnCustomBeans() {
		this.contextRunner.withUserConfiguration(CustomConfiguration.class).run((context) -> {
			assertThat(context).hasBean("customMicrometerTracer");
			assertThat(context).hasSingleBean(io.micrometer.tracing.Tracer.class);
			assertThat(context).hasBean("customEventPublisher");
			assertThat(context).hasSingleBean(EventPublisher.class);
			assertThat(context).hasBean("customOtelCurrentTraceContext");
			assertThat(context).hasSingleBean(OtelCurrentTraceContext.class);
			assertThat(context).hasBean("customOpenTelemetry");
			assertThat(context).hasSingleBean(OpenTelemetry.class);
			assertThat(context).hasBean("customSdkTracerProvider");
			assertThat(context).hasSingleBean(SdkTracerProvider.class);
			assertThat(context).hasBean("customContextPropagators");
			assertThat(context).hasSingleBean(ContextPropagators.class);
			assertThat(context).hasBean("customSampler");
			assertThat(context).hasSingleBean(Sampler.class);
			assertThat(context).hasBean("customTracer");
			assertThat(context).hasSingleBean(Tracer.class);
			assertThat(context).hasBean("customSlf4jEventListener");
			assertThat(context).hasSingleBean(Slf4JEventListener.class);
			assertThat(context).hasBean("customSlf4jBaggageEventListener");
			assertThat(context).hasSingleBean(Slf4JBaggageEventListener.class);
			assertThat(context).hasBean("customOtelPropagator");
			assertThat(context).hasSingleBean(OtelPropagator.class);
			assertThat(context).hasBean("customSpanCustomizer");
			assertThat(context).hasSingleBean(SpanCustomizer.class);
		});
	}

	@Test
	void shouldSetupDefaultResourceAttributes() {
		this.contextRunner
			.withConfiguration(
					AutoConfigurations.of(ObservationAutoConfiguration.class, MicrometerTracingAutoConfiguration.class))
			.withUserConfiguration(InMemoryRecordingSpanExporterConfiguration.class)
			.withPropertyValues("management.tracing.sampling.probability=1.0")
			.run((context) -> {
				context.getBean(io.micrometer.tracing.Tracer.class).nextSpan().name("test").end();
				InMemoryRecordingSpanExporter exporter = context.getBean(InMemoryRecordingSpanExporter.class);
				exporter.await(Duration.ofSeconds(10));
				SpanData spanData = exporter.getExportedSpans().get(0);
				Map<AttributeKey<?>, Object> expectedAttributes = Resource.getDefault()
					.merge(Resource.create(Attributes.of(ResourceAttributes.SERVICE_NAME, "application")))
					.getAttributes()
					.asMap();
				assertThat(spanData.getResource().getAttributes().asMap()).isEqualTo(expectedAttributes);
			});
	}

	@Test
	void shouldAllowMultipleSpanProcessors() {
		this.contextRunner.withUserConfiguration(CustomConfiguration.class).run((context) -> {
			assertThat(context.getBeansOfType(SpanProcessor.class)).hasSize(2);
			assertThat(context).hasBean("customSpanProcessor");
		});
	}

	@Test
	void shouldAllowMultipleTextMapPropagators() {
		this.contextRunner.withUserConfiguration(CustomConfiguration.class).run((context) -> {
			assertThat(context.getBeansOfType(TextMapPropagator.class)).hasSize(2);
			assertThat(context).hasBean("customTextMapPropagator");
		});
	}

	@Test
	void shouldNotSupplySlf4jBaggageEventListenerWhenBaggageCorrelationDisabled() {
		this.contextRunner.withPropertyValues("management.tracing.baggage.correlation.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(Slf4JBaggageEventListener.class));
	}

	@Test
	void shouldNotSupplySlf4JBaggageEventListenerWhenBaggageDisabled() {
		this.contextRunner.withPropertyValues("management.tracing.baggage.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(Slf4JBaggageEventListener.class));
	}

	@Test
	void shouldSupplyB3PropagationIfPropagationPropertySet() {
		this.contextRunner.withPropertyValues("management.tracing.propagation.type=B3").run((context) -> {
			TextMapPropagator propagator = context.getBean(TextMapPropagator.class);
			List<TextMapPropagator> injectors = getInjectors(propagator);
			assertThat(injectors).hasExactlyElementsOfTypes(B3Propagator.class, BaggageTextMapPropagator.class);
		});
	}

	@Test
	void shouldSupplyB3PropagationIfPropagationPropertySetAndBaggageDisabled() {
		this.contextRunner
			.withPropertyValues("management.tracing.propagation.type=B3", "management.tracing.baggage.enabled=false")
			.run((context) -> {
				TextMapPropagator propagator = context.getBean(TextMapPropagator.class);
				List<TextMapPropagator> injectors = getInjectors(propagator);
				assertThat(injectors).hasExactlyElementsOfTypes(B3Propagator.class);
			});
	}

	@Test
	void shouldSupplyW3CPropagationWithBaggageByDefault() {
		this.contextRunner.withPropertyValues("management.tracing.baggage.remote-fields=foo").run((context) -> {
			TextMapPropagator propagator = context.getBean(TextMapPropagator.class);
			List<TextMapPropagator> injectors = getInjectors(propagator);
			List<String> fields = new ArrayList<>();
			for (TextMapPropagator injector : injectors) {
				fields.addAll(injector.fields());
			}
			assertThat(fields).containsExactly("traceparent", "tracestate", "baggage", "foo");
		});
	}

	@Test
	void shouldSupplyW3CPropagationWithoutBaggageWhenDisabled() {
		this.contextRunner.withPropertyValues("management.tracing.baggage.enabled=false").run((context) -> {
			TextMapPropagator propagator = context.getBean(TextMapPropagator.class);
			List<TextMapPropagator> injectors = getInjectors(propagator);
			assertThat(injectors).hasExactlyElementsOfTypes(W3CTraceContextPropagator.class);
		});
	}

	private List<TextMapPropagator> getInjectors(TextMapPropagator propagator) {
		assertThat(propagator).as("propagator").isNotNull();
		if (propagator instanceof CompositeTextMapPropagator compositePropagator) {
			return compositePropagator.getInjectors().stream().toList();
		}
		fail("Expected CompositeTextMapPropagator, found %s".formatted(propagator.getClass()));
		throw new AssertionError("Unreachable");
	}

	@Configuration(proxyBeanMethods = false)
	private static class CustomConfiguration {

		@Bean
		io.micrometer.tracing.Tracer customMicrometerTracer() {
			return mock(io.micrometer.tracing.Tracer.class);
		}

		@Bean
		EventPublisher customEventPublisher() {
			return mock(EventPublisher.class);
		}

		@Bean
		OtelCurrentTraceContext customOtelCurrentTraceContext() {
			return mock(OtelCurrentTraceContext.class);
		}

		@Bean
		OpenTelemetry customOpenTelemetry() {
			return mock(OpenTelemetry.class);
		}

		@Bean
		SdkTracerProvider customSdkTracerProvider() {
			return SdkTracerProvider.builder().build();
		}

		@Bean
		ContextPropagators customContextPropagators() {
			return mock(ContextPropagators.class);
		}

		@Bean
		Sampler customSampler() {
			return mock(Sampler.class);
		}

		@Bean
		SpanProcessor customSpanProcessor() {
			return mock(SpanProcessor.class);
		}

		@Bean
		Tracer customTracer() {
			return mock(Tracer.class);
		}

		@Bean
		Slf4JEventListener customSlf4jEventListener() {
			return new Slf4JEventListener();
		}

		@Bean
		Slf4JBaggageEventListener customSlf4jBaggageEventListener() {
			return new Slf4JBaggageEventListener(List.of("alpha"));
		}

		@Bean
		OtelPropagator customOtelPropagator(ContextPropagators propagators, Tracer tracer) {
			return new OtelPropagator(propagators, tracer);
		}

		@Bean
		TextMapPropagator customTextMapPropagator() {
			return mock(TextMapPropagator.class);
		}

		@Bean
		SpanCustomizer customSpanCustomizer() {
			return mock(SpanCustomizer.class);
		}

	}

	@Configuration(proxyBeanMethods = false)
	private static class InMemoryRecordingSpanExporterConfiguration {

		@Bean
		InMemoryRecordingSpanExporter spanExporter() {
			return new InMemoryRecordingSpanExporter();
		}

	}

	private static class InMemoryRecordingSpanExporter implements SpanExporter {

		private final List<SpanData> exportedSpans = new ArrayList<>();

		private final CountDownLatch latch = new CountDownLatch(1);

		@Override
		public CompletableResultCode export(Collection<SpanData> spans) {
			this.exportedSpans.addAll(spans);
			this.latch.countDown();
			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode flush() {
			return CompletableResultCode.ofSuccess();
		}

		@Override
		public CompletableResultCode shutdown() {
			this.exportedSpans.clear();
			return CompletableResultCode.ofSuccess();
		}

		List<SpanData> getExportedSpans() {
			return this.exportedSpans;
		}

		void await(Duration timeout) throws InterruptedException, TimeoutException {
			if (!this.latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new TimeoutException("Waiting for exporting spans timed out (%s)".formatted(timeout));
			}
		}

	}

}
