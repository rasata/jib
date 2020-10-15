/*
 * Copyright 2020 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.cli.cli2;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.ContainerizerTestProxy;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.CredentialRetriever;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.configuration.ImageConfiguration;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.cloud.tools.jib.global.JibSystemProperties;
import com.google.cloud.tools.jib.registry.credentials.CredentialRetrievalException;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.RestoreSystemProperties;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import picocli.CommandLine;

public class ContainerizersTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
  // Containerizers will add system properties based on cli properties
  @Rule public RestoreSystemProperties restoreSystemProperties = new RestoreSystemProperties();

  @Test
  public void testApplyConfiguration_defaults() throws InvalidImageReferenceException {
    JibCli buildOptions = CommandLine.populateCommand(new JibCli(), "-t", "test-image-ref");

    ContainerizerTestProxy containerizer =
        new ContainerizerTestProxy(Containerizers.from(buildOptions));

    assertThat(Boolean.getBoolean(JibSystemProperties.SEND_CREDENTIALS_OVER_HTTP)).isFalse();
    assertThat(Boolean.getBoolean(JibSystemProperties.SERIALIZE)).isFalse();
    assertThat(containerizer.getToolName()).isEqualTo(VersionInfo.TOOL_NAME);
    assertThat(containerizer.getToolVersion()).isEqualTo(VersionInfo.getVersionSimple());
    assertThat(Boolean.getBoolean("sendCredentialsOverHttp")).isFalse();
    assertThat(containerizer.getAllowInsecureRegistries()).isFalse();
    assertThat(containerizer.getBaseImageLayersCacheDirectory())
        .isEqualTo(Containerizer.DEFAULT_BASE_CACHE_DIRECTORY);
    // it's a little hard to test applicationLayersCacheDirectory defaults here, so intentionally
    // skipped
    assertThat(containerizer.getAdditionalTags()).isEqualTo(ImmutableSet.of());
  }

  @Test
  public void testApplyConfiguration_withValues()
      throws InvalidImageReferenceException, CacheDirectoryCreationException {
    JibCli buildOptions =
        CommandLine.populateCommand(
            new JibCli(),
            "-t=test-image-ref",
            "--send-credentials-over-http",
            "--allow-insecure-registries",
            "--base-image-cache=./bi-cache",
            "--application-cache=./app-cache",
            "--additional-tags=tag1,tag2",
            "--serialize");

    ContainerizerTestProxy containerizer =
        new ContainerizerTestProxy(Containerizers.from(buildOptions));

    assertThat(Boolean.getBoolean(JibSystemProperties.SEND_CREDENTIALS_OVER_HTTP)).isTrue();
    assertThat(Boolean.getBoolean(JibSystemProperties.SERIALIZE)).isTrue();
    assertThat(containerizer.getAllowInsecureRegistries()).isTrue();
    assertThat(containerizer.getBaseImageLayersCacheDirectory()).isEqualTo(Paths.get("./bi-cache"));
    assertThat(containerizer.getApplicationsLayersCacheDirectory())
        .isEqualTo(Paths.get("./app-cache"));
    assertThat(containerizer.getAdditionalTags()).isEqualTo(ImmutableSet.of("tag1", "tag2"));
  }

  @Test
  public void testFrom_dockerDaemonImage() throws InvalidImageReferenceException {
    JibCli buildOptions =
        CommandLine.populateCommand(new JibCli(), "-t", "docker://gcr.io/test/test-image-ref");
    ContainerizerTestProxy containerizer =
        new ContainerizerTestProxy(Containerizers.from(buildOptions));

    assertThat(containerizer.getDescription()).isEqualTo("Building image to Docker daemon");
    ImageConfiguration config = containerizer.getImageConfiguration();

    assertThat(config.getCredentialRetrievers()).isEmpty();
    assertThat(config.getDockerClient()).isEmpty();
    assertThat(config.getImage().toString()).isEqualTo("gcr.io/test/test-image-ref");
    assertThat(config.getTarPath()).isEmpty();
  }

  @Test
  public void testFrom_tarImage() throws InvalidImageReferenceException, IOException {
    Path tarPath = temporaryFolder.getRoot().toPath().resolve("test-tar.tar");
    JibCli buildOptions =
        CommandLine.populateCommand(
            new JibCli(),
            "-t",
            "tar://" + tarPath.toAbsolutePath(),
            "--name",
            "gcr.io/test/test-image-ref");
    ContainerizerTestProxy containerizer =
        new ContainerizerTestProxy(Containerizers.from(buildOptions));

    assertThat(containerizer.getDescription()).isEqualTo("Building image tarball");
    ImageConfiguration config = containerizer.getImageConfiguration();

    assertThat(config.getCredentialRetrievers()).isEmpty();
    assertThat(config.getDockerClient()).isEmpty();
    assertThat(config.getImage().toString()).isEqualTo("gcr.io/test/test-image-ref");
    assertThat(config.getTarPath()).isEmpty(); // weird, but the way jib currently works
  }

  @Test
  public void testFrom_registryImage() throws InvalidImageReferenceException, IOException {
    JibCli buildOptions =
        CommandLine.populateCommand(new JibCli(), "-t", "registry://gcr.io/test/test-image-ref");
    ContainerizerTestProxy containerizer =
        new ContainerizerTestProxy(Containerizers.from(buildOptions));

    // description from Containerizer.java
    assertThat(containerizer.getDescription()).isEqualTo("Building and pushing image");
    ImageConfiguration config = containerizer.getImageConfiguration();

    assertThat(config.getCredentialRetrievers()).isNotEmpty();
    assertThat(config.getDockerClient()).isEmpty();
    assertThat(config.getImage().toString()).isEqualTo("gcr.io/test/test-image-ref");
    assertThat(config.getTarPath()).isEmpty();
  }

  @RunWith(MockitoJUnitRunner.class)
  public static class CrendentialConfigurationTests {

    @Rule public final TemporaryFolder testRoot = new TemporaryFolder();

    @Mock private CredentialRetrieverFactory mockCredentialRetrieverFactory;
    @Mock private CredentialRetriever mockCredentialRetriever;
    @Mock private RegistryImage mockRegistryImage;

    @Mock private CredentialRetriever mockCredentialRetrieverFromPath;
    @Mock private CredentialRetriever mockCredentialRetrieverFromString;

    @Before
    public void setupMocks() {
      when(mockCredentialRetrieverFactory.dockerConfig()).thenReturn(mockCredentialRetriever);
      when(mockCredentialRetrieverFactory.wellKnownCredentialHelpers())
          .thenReturn(mockCredentialRetriever);
      when(mockCredentialRetrieverFactory.googleApplicationDefaultCredentials())
          .thenReturn(mockCredentialRetriever);

      when(mockCredentialRetrieverFactory.dockerCredentialHelper(any(Path.class)))
          .thenReturn(mockCredentialRetrieverFromPath);
      when(mockCredentialRetrieverFactory.dockerCredentialHelper(any(String.class)))
          .thenReturn(mockCredentialRetrieverFromString);
    }

    @Test
    public void testApplyCredentialConfig_credhelperString() {
      JibCli buildOptions =
          CommandLine.populateCommand(
              new JibCli(), "--target=ignored.io/igored/ignored", "--credential-helper=any-string");
      Containerizers.applyCredentialConfig(
          mockRegistryImage, mockCredentialRetrieverFactory, buildOptions);

      // verify we added our intended credential retriever
      verify(mockRegistryImage).addCredentialRetriever(mockCredentialRetrieverFromString);
      // auto added credentials
      verify(mockRegistryImage, times(3)).addCredentialRetriever(mockCredentialRetriever);
      verifyNoMoreInteractions(mockRegistryImage);
    }

    @Test
    public void testApplyCredentialConfig_credhelperFile() throws IOException {
      Path credHelperFile = testRoot.newFile("cred-helper.sh").toPath();
      JibCli buildOptions =
          CommandLine.populateCommand(
              new JibCli(),
              "--target=ignored.io/igored/ignored",
              "--credential-helper",
              credHelperFile.toAbsolutePath().toString());
      Containerizers.applyCredentialConfig(
          mockRegistryImage, mockCredentialRetrieverFactory, buildOptions);

      // verify we added our intended credential retriever
      verify(mockRegistryImage).addCredentialRetriever(mockCredentialRetrieverFromPath);
      // auto added credentials
      verify(mockRegistryImage, times(3)).addCredentialRetriever(mockCredentialRetriever);
      verifyNoMoreInteractions(mockRegistryImage);
    }

    @Test
    public void testApplyCredentialConfig_usernamePassword() throws CredentialRetrievalException {
      JibCli buildOptions =
          CommandLine.populateCommand(
              new JibCli(),
              "--target=ignored.io/igored/ignored",
              "--username=test-username",
              "--password=test-password");
      Containerizers.applyCredentialConfig(
          mockRegistryImage, mockCredentialRetrieverFactory, buildOptions);

      // verify we added our intended credential retriever
      ArgumentCaptor<CredentialRetriever> captor =
          ArgumentCaptor.forClass(CredentialRetriever.class);
      verify(mockRegistryImage, times(4)).addCredentialRetriever(captor.capture());
      List<CredentialRetriever> configuredRetrievers = captor.getAllValues();

      assertThat(configuredRetrievers).hasSize(4);
      // remove all auto added credentials
      for (int i = 0; i < 3; i++) {
        assertThat(configuredRetrievers.remove(mockCredentialRetriever)).isTrue();
      }
      // check if our credential was added
      assertThat(configuredRetrievers.get(0).retrieve())
          .hasValue(Credential.from("test-username", "test-password"));
    }

    @Test
    public void testApplyCredentialConfig_toUsernamePassword() throws CredentialRetrievalException {
      JibCli buildOptions =
          CommandLine.populateCommand(
              new JibCli(),
              "--target=ignored.io/igored/ignored",
              "--to-username=test-username",
              "--to-password=test-password");
      Containerizers.applyCredentialConfig(
          mockRegistryImage, mockCredentialRetrieverFactory, buildOptions);

      // verify we added our intended credential retriever
      ArgumentCaptor<CredentialRetriever> captor =
          ArgumentCaptor.forClass(CredentialRetriever.class);
      verify(mockRegistryImage, times(4)).addCredentialRetriever(captor.capture());
      List<CredentialRetriever> configuredRetrievers = captor.getAllValues();

      assertThat(configuredRetrievers).hasSize(4);
      // remove all auto added credentials
      for (int i = 0; i < 3; i++) {
        assertThat(configuredRetrievers.remove(mockCredentialRetriever)).isTrue();
      }
      // check if our credential was added
      assertThat(configuredRetrievers.get(0).retrieve())
          .hasValue(Credential.from("test-username", "test-password"));
    }
  }
}
