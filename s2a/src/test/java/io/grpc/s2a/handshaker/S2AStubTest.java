/*
 * Copyright 2024 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.s2a.handshaker;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.truth.Expect;
import io.grpc.internal.SharedResourcePool;
import io.grpc.s2a.channel.S2AChannelPool;
import io.grpc.s2a.channel.S2AGrpcChannelPool;
import io.grpc.s2a.channel.S2AHandshakerServiceChannel;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link S2AStub}. */
@RunWith(JUnit4.class)
public class S2AStubTest {
  @Rule public final Expect expect = Expect.create();
  private static final String S2A_ADDRESS = "localhost:8080";
  private S2AStub stub;
  private FakeWriter writer;

  @Before
  public void setUp() {
    writer = new FakeWriter();
    stub = S2AStub.newInstanceForTesting(writer);
    writer.setReader(stub.getReader());
  }

  @Test
  public void send_receiveOkStatus() throws Exception {
    S2AChannelPool channelPool =
        S2AGrpcChannelPool.create(
            SharedResourcePool.forResource(
                S2AHandshakerServiceChannel.getChannelResource(
                    S2A_ADDRESS, /* s2aChannelCredentials= */ Optional.empty())));
    S2AServiceGrpc.S2AServiceStub serviceStub = S2AServiceGrpc.newStub(channelPool.getChannel());
    S2AStub newStub = S2AStub.newInstance(serviceStub);

    IOException expected =
        assertThrows(IOException.class, () -> newStub.send(SessionReq.getDefaultInstance()));

    assertThat(expected).hasMessageThat().contains("UNAVAILABLE");
  }

  @Test
  public void send_clientTlsConfiguration_receiveOkStatus() throws Exception {
    SessionReq req =
        SessionReq.newBuilder()
            .setGetTlsConfigurationReq(
                GetTlsConfigurationReq.newBuilder()
                    .setConnectionSide(ConnectionSide.CONNECTION_SIDE_CLIENT))
            .build();

    SessionResp resp = stub.send(req);

    SessionResp expected =
        SessionResp.newBuilder()
            .setGetTlsConfigurationResp(
                GetTlsConfigurationResp.newBuilder()
                    .setClientTlsConfiguration(
                        GetTlsConfigurationResp.ClientTlsConfiguration.newBuilder()
                            .addCertificateChain(FakeWriter.LEAF_CERT)
                            .addCertificateChain(FakeWriter.INTERMEDIATE_CERT_2)
                            .addCertificateChain(FakeWriter.INTERMEDIATE_CERT_1)
                            .setMinTlsVersion(TLSVersion.TLS_VERSION_1_3)
                            .setMaxTlsVersion(TLSVersion.TLS_VERSION_1_3)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256)))
            .build();
    assertThat(resp).ignoringRepeatedFieldOrder().isEqualTo(expected);
  }

  @Test
  public void send_serverTlsConfiguration_receiveErrorStatus() throws Exception {
    SessionReq req =
        SessionReq.newBuilder()
            .setGetTlsConfigurationReq(
                GetTlsConfigurationReq.newBuilder()
                    .setConnectionSide(ConnectionSide.CONNECTION_SIDE_SERVER))
            .build();

    SessionResp resp = stub.send(req);

    SessionResp expected =
        SessionResp.newBuilder()
            .setStatus(
                Status.newBuilder()
                    .setCode(255)
                    .setDetails("No TLS configuration for the server side."))
            .build();
    assertThat(resp).isEqualTo(expected);
  }

  @Test
  public void send_receiveErrorStatus() throws Exception {
    writer.setBehavior(FakeWriter.Behavior.ERROR_STATUS);

    SessionResp resp = stub.send(SessionReq.getDefaultInstance());

    SessionResp expected =
        SessionResp.newBuilder()
            .setStatus(
                Status.newBuilder().setCode(1).setDetails("Intended ERROR Status from FakeWriter."))
            .build();
    assertThat(resp).isEqualTo(expected);
  }

  @Test
  public void send_receiveErrorResponse() throws InterruptedException {
    writer.setBehavior(FakeWriter.Behavior.ERROR_RESPONSE);

    IOException expected =
        assertThrows(IOException.class, () -> stub.send(SessionReq.getDefaultInstance()));

    expect.that(expected).hasCauseThat().isInstanceOf(RuntimeException.class);
    expect.that(expected).hasMessageThat().contains("Intended ERROR from FakeWriter.");
  }

  @Test
  public void send_receiveCompleteStatus() throws Exception {
    writer.setBehavior(FakeWriter.Behavior.COMPLETE_STATUS);

    ConnectionIsClosedException expected =
        assertThrows(
            ConnectionIsClosedException.class, () -> stub.send(SessionReq.getDefaultInstance()));

    assertThat(expected).hasMessageThat().contains("Reading from the S2A is complete.");
  }

  @Test
  public void send_receiveUnexpectedResponse() throws Exception {
    writer.sendIoError();

    IOException expected =
        assertThrows(IOException.class, () -> stub.send(SessionReq.getDefaultInstance()));

    assertThat(expected)
        .hasMessageThat()
        .contains(
            "Received an unexpected response from a host at the S2A's address. The S2A might be"
                + " unavailable.");
  }

  @Test
  public void send_receiveManyUnexpectedResponse_expectResponsesEmpty() throws Exception {
    writer.sendIoError();
    writer.sendIoError();
    writer.sendIoError();

    IOException expected =
        assertThrows(IOException.class, () -> stub.send(SessionReq.getDefaultInstance()));

    assertThat(expected)
        .hasMessageThat()
        .contains(
            "Received an unexpected response from a host at the S2A's address. The S2A might be"
                + " unavailable.");

    assertThat(stub.getResponses()).isEmpty();
  }

  @Test
  public void send_receiveDelayedResponse() throws Exception {
    writer.sendGetTlsConfigResp();
    SessionResp resp = stub.send(SessionReq.getDefaultInstance());
    SessionResp expected =
        SessionResp.newBuilder()
            .setGetTlsConfigurationResp(
                GetTlsConfigurationResp.newBuilder()
                    .setClientTlsConfiguration(
                        GetTlsConfigurationResp.ClientTlsConfiguration.newBuilder()
                            .addCertificateChain(FakeWriter.LEAF_CERT)
                            .addCertificateChain(FakeWriter.INTERMEDIATE_CERT_2)
                            .addCertificateChain(FakeWriter.INTERMEDIATE_CERT_1)
                            .setMinTlsVersion(TLSVersion.TLS_VERSION_1_3)
                            .setMaxTlsVersion(TLSVersion.TLS_VERSION_1_3)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256)))
            .build();
    assertThat(resp).ignoringRepeatedFieldOrder().isEqualTo(expected);
  }

  @Test
  public void send_afterEarlyClose_receivesClosedException() throws InterruptedException {
    stub.close();
    expect.that(writer.isFakeWriterClosed()).isTrue();

    ConnectionIsClosedException expected =
        assertThrows(
            ConnectionIsClosedException.class, () -> stub.send(SessionReq.getDefaultInstance()));

    assertThat(expected).hasMessageThat().contains("Stream to the S2A is closed.");
  }

  @Test
  public void send_failToWrite() throws Exception {
    FailWriter failWriter = new FailWriter();
    stub = S2AStub.newInstanceForTesting(failWriter);

    IOException expected =
        assertThrows(IOException.class, () -> stub.send(SessionReq.getDefaultInstance()));

    expect.that(expected).hasCauseThat().isInstanceOf(S2AConnectionException.class);
    expect
        .that(expected)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("Could not send request to S2A.");
  }

  /** Fails whenever a write is attempted. */
  private static class FailWriter implements StreamObserver<SessionReq> {
    @Override
    public void onNext(SessionReq req) {
      assertThat(req).isNotNull();
      throw new S2AConnectionException("Could not send request to S2A.");
    }

    @Override
    public void onError(Throwable t) {
      assertThat(t).isInstanceOf(S2AConnectionException.class);
    }

    @Override
    public void onCompleted() {
      throw new UnsupportedOperationException();
    }
  }
}