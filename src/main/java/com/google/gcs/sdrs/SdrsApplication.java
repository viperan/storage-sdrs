/*
 * Copyright 2019 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and limitations under the License.
 *
 * Any software provided by Google hereunder is distributed “AS IS”,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, and is not intended for production use.
 *
 */

package com.google.gcs.sdrs;

import com.google.gcs.sdrs.JobScheduler.JobScheduler;
import com.google.gcs.sdrs.mq.PubSubMessageQueueManagerImpl;
import com.google.gcs.sdrs.runners.RuleExecutionRunner;
import com.google.gcs.sdrs.runners.ValidationRunner;
import com.google.gcs.sdrs.server.ServerShutdownHook;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.builder.fluent.Configurations;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * The main startup class for the SDRS service.
 */
public class SdrsApplication {

  private static final Logger logger = LoggerFactory.getLogger(SdrsApplication.class);

  private static HttpServer server;
  private static Configuration xmlConfig;

  /**
   * Starts the SDRS service
   * @param args Application startup arguments
   */
  public static void main(String[] args) {
    logger.info("Starting SDRS...");

    try {
      xmlConfig = new Configurations().xml("applicationConfig.xml");
    } catch(ConfigurationException ex) {
      logger.error("The server could not start because the configuration file could not be read: "
          + ex.getCause());
    }

    startWebServer();
    registerPubSub();
    scheduleExecutionServiceJob();
    scheduleValidationServiceJob();
  }

  /**
   * Triggers the shutdown hook and gracefully shuts down the SDRS service
   */
  static void shutdown() {
    System.exit(0);
  }

  /**
   * Loads necessary configurations and starts the web server
   */
  private static void startWebServer() {
    try {

      // Read config values
      Boolean useHttps = xmlConfig.getBoolean("serverConfig.useHttps");
      String hostName = xmlConfig.getString("serverConfig.address");
      int port = xmlConfig.getInt("serverConfig.port");
      long shutdownGracePeriodInSeconds = xmlConfig
          .getLong("serverConfig.shutdownGracePeriodInSeconds");

      // Build server URI
      URI baseUri = UriBuilder.fromUri((useHttps ? "https://" : "http://") + hostName + "/")
          .port(port).build();

      server = GrizzlyHttpServerFactory.createHttpServer(baseUri, new AppResourceConfig());

      // Register shutdown hook so the monitoring thread is killed when the app is stopped
      Runtime.getRuntime().addShutdownHook(new Thread(
          new ServerShutdownHook(server, shutdownGracePeriodInSeconds, false),
          "shutdownHook"));

      server.start();
      logger.info("SDRS Web Server Started.");
    } catch (IOException ex) {
      logger.error("An error occurred during web server start up: " + ex.getCause());
    }
  }

  private static void scheduleExecutionServiceJob(){
    JobScheduler scheduler = JobScheduler.getInstance();

    int initialDelay = xmlConfig.getInt("scheduler.task.ruleExecution.initialDelay");
    int frequency = xmlConfig.getInt("scheduler.task.ruleExecution.frequency");
    TimeUnit timeUnit = TimeUnit.valueOf(xmlConfig.getString("scheduler.task.ruleExecution.timeUnit"));

    scheduler.submitScheduledJob(new RuleExecutionRunner(), initialDelay, frequency, timeUnit);
    logger.info("Rule execution scheduled successfully.");
  }

  private static void scheduleValidationServiceJob(){
    JobScheduler scheduler = JobScheduler.getInstance();

    int initialDelay = xmlConfig.getInt("scheduler.task.validationService.initialDelay");
    int frequency = xmlConfig.getInt("scheduler.task.validationService.frequency");
    TimeUnit timeUnit = TimeUnit.valueOf(xmlConfig.getString("scheduler.task.validationService.timeUnit"));

    scheduler.submitScheduledJob(new ValidationRunner(), initialDelay, frequency, timeUnit);
    logger.info("Validation service scheduled successfully.");
  }

  public static Configuration getAppConfig() {
    if (xmlConfig == null) {
      try {
        xmlConfig = new Configurations().xml("applicationConfig.xml");
      } catch (ConfigurationException ex) {
        logger.error("Failed to load applicationConfig.xml");
      }

    }
    return xmlConfig;
  }

  private static void registerPubSub() {
    if (PubSubMessageQueueManagerImpl.getInstance().getPublisher() == null) {
      logger.error("Failed to register pubsub publisher");
    }
  }

  private static void createServiceInstances(){
    /*
      TODO Wire up any injection of services once they're needed.
      May be unnecessary if we add a DI framework
    */
  }
}
