/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eagle.app.service.impl;

import com.codahale.metrics.health.HealthCheck;
import com.google.inject.Inject;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.dropwizard.setup.Environment;
import org.apache.eagle.app.service.ApplicationHealthCheckService;
import org.apache.eagle.app.service.ApplicationProviderService;
import org.apache.eagle.app.spi.ApplicationProvider;
import org.apache.eagle.metadata.model.ApplicationEntity;
import org.apache.eagle.metadata.service.ApplicationEntityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ApplicationHealthCheckServiceImpl extends ApplicationHealthCheckService {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationHealthCheckServiceImpl.class);

    private final ApplicationProviderService applicationProviderService;
    private final ApplicationEntityService applicationEntityService;
    private final Config config;
    private Environment environment;
    private Map<String, HealthCheck> appHealthChecks = new HashMap<>();
    private final Object lock = new Object();
    private int initialDelay = 10;
    private int period = 10;

    @Inject
    public ApplicationHealthCheckServiceImpl(ApplicationProviderService applicationProviderService,
                                             ApplicationEntityService applicationEntityService,
                                             Config config) {
        this.applicationProviderService = applicationProviderService;
        this.applicationEntityService = applicationEntityService;
        this.config = config;
    }

    @Override
    public void init(Environment environment) {
        this.environment = environment;
        registerAll();
    }

    private void registerAll() {
        Collection<ApplicationEntity> applicationEntities = applicationEntityService.findAll();
        applicationEntities.forEach(this::register);
    }

    @Override
    public void register(ApplicationEntity appEntity) {
        if (environment == null) {
            LOG.warn("environment is null, can not register");
            return;
        }
        ApplicationProvider<?> appProvider = applicationProviderService.getApplicationProviderByType(appEntity.getDescriptor().getType());
        HealthCheck applicationHealthCheck = appProvider.getAppHealthCheck(
                ConfigFactory.parseMap(appEntity.getConfiguration())
                        .withFallback(config)
                        .withFallback(ConfigFactory.parseMap(appEntity.getContext()))
        );
        this.environment.healthChecks().register(appEntity.getAppId(), applicationHealthCheck);
        synchronized (lock) {
            if (!appHealthChecks.containsKey(appEntity.getAppId())) {
                appHealthChecks.put(appEntity.getAppId(), applicationHealthCheck);
                LOG.info("successfully register health check for {}", appEntity.getAppId());
            }
        }
    }

    @Override
    public void unregister(ApplicationEntity appEntity) {
        if (environment == null) {
            LOG.warn("environment is null, can not unregister");
            return;
        }
        this.environment.healthChecks().unregister(appEntity.getAppId());
        synchronized (lock) {
            appHealthChecks.remove(appEntity.getAppId());
        }
        LOG.info("successfully unregister health check for {}", appEntity.getAppId());
    }

    @Override
    protected void runOneIteration() throws Exception {
        LOG.info("start application health check");
        registerAll();
        synchronized (lock) {
            for (String appId : appHealthChecks.keySet()) {
                LOG.info("check application {}", appId);
                HealthCheck.Result result = appHealthChecks.get(appId).execute();
                if (result.isHealthy()) {
                    LOG.info("application {} is healthy", appId);
                } else {
                    LOG.warn("application {} is not healthy, {}", appId, result.getMessage(), result.getError());
                }
            }
        }
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedRateSchedule(initialDelay, period, TimeUnit.SECONDS);
    }
}
