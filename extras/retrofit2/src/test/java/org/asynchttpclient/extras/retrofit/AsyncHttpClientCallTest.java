/*
 * Copyright (c) 2017 AsyncHttpClient Project. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package org.asynchttpclient.extras.retrofit;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import lombok.val;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.Response;
import org.mockito.ArgumentCaptor;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.asynchttpclient.extras.retrofit.AsyncHttpClientCall.runConsumer;
import static org.asynchttpclient.extras.retrofit.AsyncHttpClientCall.runConsumers;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

public class AsyncHttpClientCallTest {
    static final Request REQUEST = new Request.Builder().url("http://www.google.com/").build();

    @Test(expectedExceptions = NullPointerException.class, dataProvider = "first")
    void builderShouldThrowInCaseOfMissingProperties(AsyncHttpClientCall.AsyncHttpClientCallBuilder builder) {
        builder.build();
    }

    @DataProvider(name = "first")
    Object[][] dataProviderFirst() {
        val httpClient = mock(AsyncHttpClient.class);

        return new Object[][]{
                {AsyncHttpClientCall.builder()},
                {AsyncHttpClientCall.builder().request(REQUEST)},
                {AsyncHttpClientCall.builder().httpClient(httpClient)}
        };
    }

    @Test(dataProvider = "second")
    void shouldInvokeConsumersOnEachExecution(Consumer<AsyncCompletionHandler<?>> handlerConsumer,
                                              int expectedStarted,
                                              int expectedOk,
                                              int expectedFailed) {
        // given

        // counters
        val numStarted = new AtomicInteger();
        val numOk = new AtomicInteger();
        val numFailed = new AtomicInteger();
        val numRequestCustomizer = new AtomicInteger();

        // prepare http client mock
        val httpClient = mock(AsyncHttpClient.class);

        val mockRequest = mock(org.asynchttpclient.Request.class);
        when(mockRequest.getHeaders()).thenReturn(EmptyHttpHeaders.INSTANCE);

        val brb = new BoundRequestBuilder(httpClient, mockRequest);
        when(httpClient.prepareRequest((org.asynchttpclient.RequestBuilder) any())).thenReturn(brb);

        when(httpClient.executeRequest((org.asynchttpclient.Request) any(), any())).then(invocationOnMock -> {
            @SuppressWarnings("rawtypes")
            AsyncCompletionHandler<?> handler = invocationOnMock.getArgument(1);
            handlerConsumer.accept(handler);
            return null;
        });

        // create call instance
        val call = AsyncHttpClientCall.builder()
                .httpClient(httpClient)
                .request(REQUEST)
                .onRequestStart(e -> numStarted.incrementAndGet())
                .onRequestFailure(t -> numFailed.incrementAndGet())
                .onRequestSuccess(r -> numOk.incrementAndGet())
                .requestCustomizer(rb -> numRequestCustomizer.incrementAndGet())
                .executeTimeoutMillis(1000)
                .build();

        // when
        Assert.assertFalse(call.isExecuted());
        Assert.assertFalse(call.isCanceled());
        try {
            call.execute();
        } catch (Exception e) {
        }

        // then
        assertTrue(call.isExecuted());
        Assert.assertFalse(call.isCanceled());
        assertTrue(numRequestCustomizer.get() == 1); // request customizer must be always invoked.
        assertTrue(numStarted.get() == expectedStarted);
        assertTrue(numOk.get() == expectedOk);
        assertTrue(numFailed.get() == expectedFailed);

        // try with non-blocking call
        numStarted.set(0);
        numOk.set(0);
        numFailed.set(0);
        val clonedCall = call.clone();

        // when
        clonedCall.enqueue(null);

        // then
        assertTrue(clonedCall.isExecuted());
        Assert.assertFalse(clonedCall.isCanceled());
        assertTrue(numRequestCustomizer.get() == 2); // request customizer must be always invoked.
        assertTrue(numStarted.get() == expectedStarted);
        assertTrue(numOk.get() == expectedOk);
        assertTrue(numFailed.get() == expectedFailed);
    }

    @DataProvider(name = "second")
    Object[][] dataProviderSecond() {
        // mock response
        val response = mock(Response.class);
        when(response.getStatusCode()).thenReturn(200);
        when(response.getStatusText()).thenReturn("OK");
        when(response.getHeaders()).thenReturn(EmptyHttpHeaders.INSTANCE);

        Consumer<AsyncCompletionHandler<?>> okConsumer = handler -> {
            try {
                handler.onCompleted(response);
            } catch (Exception e) {
            }
        };
        Consumer<AsyncCompletionHandler<?>> failedConsumer = handler -> handler.onThrowable(new TimeoutException("foo"));

        return new Object[][]{
                {okConsumer, 1, 1, 0},
                {failedConsumer, 1, 0, 1}
        };
    }

    @Test(dataProvider = "third")
    void toIOExceptionShouldProduceExpectedResult(Throwable exception) {
        // given
        val call = AsyncHttpClientCall.builder()
                .httpClient(mock(AsyncHttpClient.class))
                .request(REQUEST)
                .build();

        // when
        val result = call.toIOException(exception);

        // then
        Assert.assertNotNull(result);
        assertTrue(result instanceof IOException);

        if (exception.getMessage() == null) {
            assertTrue(result.getMessage() == exception.toString());
        } else {
            assertTrue(result.getMessage() == exception.getMessage());
        }
    }

    @DataProvider(name = "third")
    Object[][] dataProviderThird() {
        return new Object[][]{
                {new IOException("foo")},
                {new RuntimeException("foo")},
                {new IllegalArgumentException("foo")},
                {new ExecutionException(new RuntimeException("foo"))},
        };
    }

    @Test(dataProvider = "4th")
    <T> void runConsumerShouldTolerateBadConsumers(Consumer<T> consumer, T argument) {
        // when
        runConsumer(consumer, argument);

        // then
        assertTrue(true);
    }

    @DataProvider(name = "4th")
    Object[][] dataProvider4th() {
        return new Object[][]{
                {null, null},
                {(Consumer<String>) s -> s.trim(), null},
                {null, "foobar"},
                {(Consumer<String>) s -> doThrow("trololo"), null},
                {(Consumer<String>) s -> doThrow("trololo"), "foo"},
        };
    }

    @Test(dataProvider = "5th")
    <T> void runConsumersShouldTolerateBadConsumers(Collection<Consumer<T>> consumers, T argument) {
        // when
        runConsumers(consumers, argument);

        // then
        assertTrue(true);
    }

    @DataProvider(name = "5th")
    Object[][] dataProvider5th() {
        return new Object[][]{
                {null, null},
                {Arrays.asList((Consumer<String>) s -> s.trim()), null},
                {Arrays.asList(s -> s.trim(), null, (Consumer<String>) s -> s.isEmpty()), null},
                {null, "foobar"},
                {Arrays.asList((Consumer<String>) s -> doThrow("trololo")), null},
                {Arrays.asList((Consumer<String>) s -> doThrow("trololo")), "foo"},
        };
    }

    @Test
    public void contentTypeHeaderIsPassedInRequest() throws Exception {
        Request request = requestWithBody();

        ArgumentCaptor<org.asynchttpclient.Request> capture = ArgumentCaptor.forClass(org.asynchttpclient.Request.class);
        AsyncHttpClient client = mock(AsyncHttpClient.class);

        givenResponseIsProduced(client, aResponse());

        whenRequestIsMade(client, request);

        verify(client).executeRequest(capture.capture(), any());

        org.asynchttpclient.Request ahcRequest = capture.getValue();

        assertTrue(ahcRequest.getHeaders().containsValue("accept", "application/vnd.hal+json", true),
                "Accept header not found");
        assertEquals(ahcRequest.getHeaders().get("content-type"), "application/json",
                "Content-Type header not found");
    }

    @Test
    public void contenTypeIsOptionalInResponse() throws Exception {
        AsyncHttpClient client = mock(AsyncHttpClient.class);

        givenResponseIsProduced(client, responseWithBody(null, "test"));

        okhttp3.Response response = whenRequestIsMade(client, REQUEST);

        assertEquals(response.code(), 200);
        assertEquals(response.header("Server"), "nginx");
        assertEquals(response.body().contentType(), null);
        assertEquals(response.body().string(), "test");
    }

    @Test
    public void contentTypeIsProperlyParsedIfPresent() throws Exception {
        AsyncHttpClient client = mock(AsyncHttpClient.class);

        givenResponseIsProduced(client, responseWithBody("text/plain", "test"));

        okhttp3.Response response = whenRequestIsMade(client, REQUEST);

        assertEquals(response.code(), 200);
        assertEquals(response.header("Server"), "nginx");
        assertEquals(response.body().contentType(), MediaType.parse("text/plain"));
        assertEquals(response.body().string(), "test");

    }

    @Test
    public void bodyIsNotNullInResponse() throws Exception {
        AsyncHttpClient client = mock(AsyncHttpClient.class);

        givenResponseIsProduced(client, responseWithNoBody());

        okhttp3.Response response = whenRequestIsMade(client, REQUEST);

        assertEquals(response.code(), 200);
        assertEquals(response.header("Server"), "nginx");
        assertNotEquals(response.body(), null);
    }

    private void givenResponseIsProduced(AsyncHttpClient client, Response response) {
        when(client.executeRequest(any(org.asynchttpclient.Request.class), any())).thenAnswer(invocation -> {
            AsyncCompletionHandler<Response> handler = invocation.getArgument(1);
            handler.onCompleted(response);
            return null;
        });
    }

    private okhttp3.Response whenRequestIsMade(AsyncHttpClient client, Request request) throws IOException {
        AsyncHttpClientCall call = AsyncHttpClientCall.builder().httpClient(client).request(request).build();

        return call.execute();
    }

    private Request requestWithBody() {
        return new Request.Builder()
                .post(RequestBody.create(MediaType.parse("application/json"), "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8)))
                .url("http://example.org/resource")
                .addHeader("Accept", "application/vnd.hal+json")
                .build();
    }

    private Response aResponse() {
        Response response = mock(Response.class);
        when(response.getStatusCode()).thenReturn(200);
        when(response.getStatusText()).thenReturn("OK");
        when(response.hasResponseHeaders()).thenReturn(true);
        when(response.getHeaders()).thenReturn(new DefaultHttpHeaders()
                .add("Server", "nginx")
        );
        when(response.hasResponseBody()).thenReturn(false);
        return response;
    }

    private Response responseWithBody(String contentType, String content) {
        Response response = aResponse();
        when(response.hasResponseBody()).thenReturn(true);
        when(response.getContentType()).thenReturn(contentType);
        when(response.getResponseBodyAsBytes()).thenReturn(content.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private Response responseWithNoBody() {
        Response response = aResponse();
        when(response.hasResponseBody()).thenReturn(false);
        when(response.getContentType()).thenReturn(null);
        return response;
    }

    private void doThrow(String message) {
        throw new RuntimeException(message);
    }

    /**
     * Creates consumer that increments counter when it's called.
     *
     * @param counter counter that is going to be called
     * @param <T>     consumer type
     * @return consumer.
     */
    protected static <T> Consumer<T> createConsumer(AtomicInteger counter) {
        return e -> counter.incrementAndGet();
    }
}
