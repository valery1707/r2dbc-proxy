/*
 * Copyright 2022 the original author or authors.
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

package io.r2dbc.proxy.observation;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import io.micrometer.tracing.test.simple.TracingAssertions;
import io.r2dbc.proxy.core.Bindings;
import io.r2dbc.proxy.core.BoundValue;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.proxy.test.MockQueryExecutionInfo;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test for {@link ObservationProxyExecutionListener}.
 *
 * @author Tadaya Tsuyukubo
 */
class ObservationProxyExecutionListenerTest {

    private static Propagator NOOP_PROPAGATOR = new Propagator() {

        @Override
        public List<String> fields() {
            return Collections.emptyList();
        }

        @Override
        public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {

        }

        @Override
        public <C> Span.Builder extract(C carrier, Getter<C> getter) {
            return Span.Builder.NOOP;
        }
    };

    private SimpleTracer tracer;

    private ObservationRegistry registry;

    @BeforeEach
    void setup() {
        this.tracer = new SimpleTracer();
        this.registry = ObservationRegistry.create();
    }

    @Test
    void query() {
        this.registry.observationConfig().observationHandler(new PropagatingSenderTracingObservationHandler<R2dbcContext>(this.tracer, NOOP_PROPAGATOR));

        ConnectionFactoryMetadata metadata = mock(ConnectionFactoryMetadata.class);
        when(metadata.getName()).thenReturn("my-db");
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.getMetadata()).thenReturn(metadata);
        String url = "r2dbc:postgresql://192.168.1.1:5432/sample";

        ObservationProxyExecutionListener listener = new ObservationProxyExecutionListener(this.registry, connectionFactory, url);

        Bindings bindings = new Bindings();
        bindings.addIndexBinding(Bindings.indexBinding(0, BoundValue.value("foo")));
        bindings.addIndexBinding(Bindings.indexBinding(1, BoundValue.value(100)));
        QueryInfo queryInfo = new QueryInfo("SELECT 1");
        queryInfo.getBindingsList().add(bindings);

        QueryExecutionInfo queryExecutionInfo = MockQueryExecutionInfo.builder().threadName("my-thread").queries(Arrays.asList(queryInfo)).build();

        listener.beforeQuery(queryExecutionInfo);
        assertThat(this.tracer.currentSpan()).as("r2dbc does not open scope").isNull();
        listener.afterQuery(queryExecutionInfo);
        assertThat(this.tracer.currentSpan()).isNull();

        TracingAssertions.assertThat(this.tracer).onlySpan()
            .hasNameEqualTo("query")
            .hasRemoteServiceNameEqualTo("my-db")
            .hasIpEqualTo("192.168.1.1")
            .hasPortEqualTo(5432)
            .hasTag("r2dbc.connection", "my-db")
            .hasTag("r2dbc.thread", "my-thread")
            .hasTag("r2dbc.query[0]", "SELECT 1")
            .doesNotHaveTagWithKey("r2dbc.params[0]");
    }

    @Test
    void queryWithIncludeParameterValues() {
        this.registry.observationConfig().observationHandler(new PropagatingSenderTracingObservationHandler<R2dbcContext>(this.tracer, NOOP_PROPAGATOR));

        ConnectionFactoryMetadata metadata = mock(ConnectionFactoryMetadata.class);
        when(metadata.getName()).thenReturn("my-db");
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.getMetadata()).thenReturn(metadata);
        String url = "r2dbc:postgresql://192.168.1.1:5432/sample";

        ObservationProxyExecutionListener listener = new ObservationProxyExecutionListener(this.registry, connectionFactory, url);
        listener.setIncludeParameterValues(true);

        Bindings bindings = new Bindings();
        bindings.addIndexBinding(Bindings.indexBinding(0, BoundValue.value("foo")));
        bindings.addIndexBinding(Bindings.indexBinding(1, BoundValue.value(100)));
        QueryInfo queryInfo = new QueryInfo("SELECT 1");
        queryInfo.getBindingsList().add(bindings);

        QueryExecutionInfo queryExecutionInfo = MockQueryExecutionInfo.builder().threadName("my-thread").queries(Arrays.asList(queryInfo)).build();

        listener.beforeQuery(queryExecutionInfo);
        listener.afterQuery(queryExecutionInfo);

        TracingAssertions.assertThat(this.tracer).onlySpan().hasTag("r2dbc.params[0]", "(foo,100)");
    }

}
