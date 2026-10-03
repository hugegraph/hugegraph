/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hugegraph.unit.rest;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import org.apache.hugegraph.rest.AbstractRestClient;
import org.apache.hugegraph.rest.RestClientConfig;
import org.apache.hugegraph.rest.RestHeaders;
import org.junit.Assert;
import org.junit.Test;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.GzipSource;
import okio.Okio;

public class AbstractRestClientTest {

    @Test
    public void testRequestBodyWithAsciiDefaultCharset() throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path",
                                              System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-Dfile.encoding=US-ASCII", "-cp", classpath,
                                             AbstractRestClientTest.class.getName())
                          .redirectErrorStream(true).start();
        try {
            Assert.assertTrue("ASCII request body probe timed out",
                              process.waitFor(30, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(),
                                       StandardCharsets.UTF_8);
            Assert.assertEquals(output, 0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }

    public static void main(String[] args) throws IOException {
        Assert.assertEquals(StandardCharsets.US_ASCII, Charset.defaultCharset());
        AbstractRestClientTest test = new AbstractRestClientTest();
        test.testJsonRequestBodyUsesUtf8();
        test.testTextRequestBodyUsesUtf8();
        test.testGzipRequestBodyUsesUtf8();
        test.testRequestBodyUsesDeclaredCharset();
    }

    @Test
    public void testJsonRequestBodyUsesUtf8() throws IOException {
        String json = "{\"name\":\"你好\"}";
        assertRequestBody(Collections.singletonMap("name", "你好"), null, json);
        assertRequestBody(json, null, json);
        assertRequestBody(null, null, "{}");
    }

    @Test
    public void testTextRequestBodyUsesUtf8() throws IOException {
        RestHeaders headers = new RestHeaders().add(RestHeaders.CONTENT_TYPE, "text/plain");
        assertRequestBody("你好", headers, "你好");
    }

    @Test
    public void testRequestBodyUsesDeclaredCharset() throws IOException {
        RestHeaders latin1 = new RestHeaders().add(RestHeaders.CONTENT_TYPE,
                                                   "text/plain; charset=ISO-8859-1");
        assertRequestBody("café", latin1, new byte[]{0x63, 0x61, 0x66, (byte) 0xe9});
        RestHeaders utf8 = new RestHeaders().add(RestHeaders.CONTENT_TYPE,
                                                 "text/plain; charset=UTF-8");
        assertRequestBody("你好", utf8, "你好");
    }

    @Test
    public void testGzipRequestBodyUsesUtf8() throws IOException {
        RestHeaders headers = new RestHeaders().add(RestHeaders.CONTENT_ENCODING, "gzip");
        assertRequestBody(Collections.singletonMap("name", "你好"), headers,
                          "{\"name\":\"你好\"}");
    }

    private static void assertRequestBody(Object body, RestHeaders headers, String expected)
            throws IOException {
        assertRequestBody(body, headers, expected.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertRequestBody(Object body, RestHeaders headers, byte[] expected)
            throws IOException {
        String contentType = headers == null ? null : headers.get(RestHeaders.CONTENT_TYPE);
        String expectedType = contentType == null ? RestHeaders.APPLICATION_JSON : contentType;
        RestClientConfig config = RestClientConfig.builder().builderCallback(builder -> {
            builder.addInterceptor(chain -> {
                Request request = chain.request();
                Assert.assertNotNull(request.body());
                Assert.assertEquals(expectedType, request.body().contentType().toString());
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                byte[] actual;
                if (headers != null && "gzip".equals(headers.get(RestHeaders.CONTENT_ENCODING))) {
                    try (GzipSource source = new GzipSource(buffer)) {
                        actual = Okio.buffer(source).readByteArray();
                    }
                } else {
                    actual = buffer.readByteArray();
                }
                Assert.assertArrayEquals(expected, actual);
                return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                                             .code(200).message("OK")
                                             .body(ResponseBody.create("{}",
                                                   MediaType.parse(RestHeaders.APPLICATION_JSON)))
                                             .build();
            });
        }).build();
        AbstractRestClient client = new AbstractRestClient("http://localhost", config) {
            @Override
            protected void checkStatus(Response response, int... statuses) {
                Assert.assertEquals(200, response.code());
            }
        };
        try {
            client.post("vertices", body, headers);
            client.put("vertices", "1", body, headers);
        } finally {
            client.close();
        }
    }

    @Test
    public void testEncodeWithSpaces() {
        String raw = "hello world";
        String expected = "hello%2Bworld";
        String encoded = AbstractRestClient.encode(raw);
        Assert.assertEquals(expected, encoded);
    }

    @Test
    public void testEncodeWithSpecialCharacters() {
        String raw = "hello@world!";
        String expected = "hello%40world%21";
        String encoded = AbstractRestClient.encode(raw);
        Assert.assertEquals(expected, encoded);
    }

    @Test
    public void testEncodeWithChineseCharacters() {
        String raw = "你好";
        String expected = "%E4%BD%A0%E5%A5%BD";
        String encoded = AbstractRestClient.encode(raw);
        Assert.assertEquals(expected, encoded);
    }

    @Test
    public void testEncodeWithNullInput() {
        String raw = null;
        Assert.assertThrows(NullPointerException.class, () -> {
            AbstractRestClient.encode(raw);
        });
    }
}
