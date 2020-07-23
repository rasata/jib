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

import static com.google.cloud.tools.jib.api.Jib.DOCKER_DAEMON_IMAGE_PREFIX;
import static com.google.cloud.tools.jib.api.Jib.REGISTRY_IMAGE_PREFIX;
import static com.google.cloud.tools.jib.api.Jib.TAR_IMAGE_PREFIX;

import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.DockerDaemonImage;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.LogEvent;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.TarImage;
import com.google.cloud.tools.jib.cli.cli2.logging.CliLogger;
import com.google.cloud.tools.jib.event.events.ProgressEvent;
import com.google.cloud.tools.jib.event.progress.ProgressEventHandler;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.cloud.tools.jib.global.JibSystemProperties;
import com.google.cloud.tools.jib.plugins.common.logging.ConsoleLogger;
import com.google.cloud.tools.jib.plugins.common.logging.ProgressDisplayGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Helper class for creating Containerizers from JibCli specifications. */
public class Containerizers {

  /**
   * Create a Containerizer from a jibcli command line specification.
   *
   * @param buildOptions JibCli options
   * @return a populated Containerizer
   * @throws InvalidImageReferenceException if the image reference could not be parsed
   */
  public static Containerizer from(JibCli buildOptions) throws InvalidImageReferenceException {
    ConsoleLogger logger =
        CliLogger.newLogger(buildOptions.getVerbosity(), buildOptions.getConsoleOutput());

    Containerizer containerizer = create(buildOptions, logger);

    applyLogger(containerizer, logger);
    applyConfiguration(containerizer, buildOptions);

    return containerizer;
  }

  static Containerizer create(JibCli buildOptions, ConsoleLogger logger)
      throws InvalidImageReferenceException {
    String imageSpec = buildOptions.getTargetImage();
    if (imageSpec.startsWith(DOCKER_DAEMON_IMAGE_PREFIX)) {
      // TODO: allow setting docker env and docker executable (along with path/env)
      return Containerizer.to(
          DockerDaemonImage.named(imageSpec.replaceFirst(DOCKER_DAEMON_IMAGE_PREFIX, "")));
    }
    if (imageSpec.startsWith(TAR_IMAGE_PREFIX)) {
      return Containerizer.to(
          TarImage.at(Paths.get(imageSpec.replaceFirst(TAR_IMAGE_PREFIX, "")))
              .named(buildOptions.getName()));
    }
    ImageReference imageReference =
        ImageReference.parse(imageSpec.replaceFirst(REGISTRY_IMAGE_PREFIX, ""));
    RegistryImage registryImage = RegistryImage.named(imageReference);
    applyCredentialConfig(
        registryImage,
        CredentialRetrieverFactory.forImage(
            imageReference, logEvent -> logger.log(logEvent.getLevel(), logEvent.getMessage())),
        buildOptions);
    return Containerizer.to(registryImage);
  }

  static void applyCredentialConfig(
      RegistryImage registryImage, CredentialRetrieverFactory factory, JibCli buildOptions) {
    if (buildOptions.getUsernamePassword().isPresent()) {
      registryImage.addCredentialRetriever(buildOptions::getUsernamePassword);
    } else if (buildOptions.getToUsernamePassword().isPresent()) {
      registryImage.addCredentialRetriever(buildOptions::getToUsernamePassword);
    }
    for (String credentialHelper : buildOptions.getCredentialHelpers()) {
      Path path = Paths.get(credentialHelper);
      if (Files.exists(path)) {
        registryImage.addCredentialRetriever(factory.dockerCredentialHelper(path));
      } else {
        registryImage.addCredentialRetriever(factory.dockerCredentialHelper(credentialHelper));
      }
    }
    // then add any other known helpers
    registryImage.addCredentialRetriever(factory.dockerConfig());
    registryImage.addCredentialRetriever(factory.wellKnownCredentialHelpers());
    registryImage.addCredentialRetriever(factory.googleApplicationDefaultCredentials());
  }

  static void applyConfiguration(Containerizer containerizer, JibCli buildOptions) {
    containerizer.setToolName(VersionInfo.TOOL_NAME);
    containerizer.setToolVersion(VersionInfo.getVersionSimple());

    // TODO: it's strange that we use system properties to set these
    // TODO: perhaps we should expose these as configuration options on the containerizer
    if (buildOptions.isSendCredentialsOverHttp()) {
      System.setProperty(JibSystemProperties.SEND_CREDENTIALS_OVER_HTTP, Boolean.TRUE.toString());
    }
    if (buildOptions.isSerialize()) {
      System.setProperty(JibSystemProperties.SERIALIZE, Boolean.TRUE.toString());
    }

    containerizer.setAllowInsecureRegistries(buildOptions.isAllowInsecureRegistries());
    buildOptions.getBaseImageCache().ifPresent(containerizer::setBaseImageLayersCache);
    buildOptions.getApplicationCache().ifPresent(containerizer::setApplicationLayersCache);

    for (String tag : buildOptions.getAdditionalTags()) {
      containerizer.withAdditionalTag(tag);
    }
  }

  static void applyLogger(Containerizer containerizer, ConsoleLogger consoleLogger) {
    containerizer
        .addEventHandler(
            LogEvent.class,
            logEvent -> consoleLogger.log(logEvent.getLevel(), logEvent.getMessage()))
        .addEventHandler(
            ProgressEvent.class,
            new ProgressEventHandler(
                update -> {
                  List<String> footer =
                      ProgressDisplayGenerator.generateProgressDisplay(
                          update.getProgress(), update.getUnfinishedLeafTasks());
                  footer.add("");
                  consoleLogger.setFooter(footer);
                }));
  }
}
