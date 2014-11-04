/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.replication.agent.impl;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.replication.agent.ReplicationAgent;
import org.apache.sling.replication.agent.ReplicationAgentException;
import org.apache.sling.replication.agent.ReplicationRequestAuthorizationStrategy;
import org.apache.sling.replication.communication.ReplicationRequest;
import org.apache.sling.replication.communication.ReplicationResponse;
import org.apache.sling.replication.component.ManagedReplicationComponent;
import org.apache.sling.replication.event.impl.ReplicationEventFactory;
import org.apache.sling.replication.event.ReplicationEventType;
import org.apache.sling.replication.packaging.*;
import org.apache.sling.replication.queue.*;
import org.apache.sling.replication.serialization.ReplicationPackageBuildingException;
import org.apache.sling.replication.trigger.ReplicationRequestHandler;
import org.apache.sling.replication.trigger.ReplicationTrigger;
import org.apache.sling.replication.trigger.ReplicationTriggerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Basic implementation of a {@link ReplicationAgent}
 */
public class SimpleReplicationAgent implements ReplicationAgent, ManagedReplicationComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ReplicationQueueProvider queueProvider;

    private final boolean passive;
    private final ReplicationPackageImporter replicationPackageImporter;
    private final ReplicationPackageExporter replicationPackageExporter;

    private final ReplicationQueueDistributionStrategy queueDistributionStrategy;

    private final ReplicationEventFactory replicationEventFactory;

    private final List<ReplicationTrigger> triggers;

    private final String name;

    private final ReplicationRequestAuthorizationStrategy replicationRequestAuthorizationStrategy;
    private final ResourceResolverFactory resourceResolverFactory;
    private final String subServiceName;
    private AgentBasedRequestHandler agentBasedRequestHandler;

    public SimpleReplicationAgent(String name,
                                  boolean passive,
                                  String subServiceName,
                                  ReplicationPackageImporter replicationPackageImporter,
                                  ReplicationPackageExporter replicationPackageExporter,
                                  ReplicationRequestAuthorizationStrategy replicationRequestAuthorizationStrategy,
                                  ReplicationQueueProvider queueProvider,
                                  ReplicationQueueDistributionStrategy queueDistributionStrategy,
                                  ReplicationEventFactory replicationEventFactory,
                                  ResourceResolverFactory resourceResolverFactory,
                                  List<ReplicationTrigger> triggers) {


        // check configuration is valid
        if (name == null
                || replicationPackageImporter == null
                || replicationPackageExporter == null
                || subServiceName == null
                || replicationRequestAuthorizationStrategy == null
                || queueProvider == null
                || queueDistributionStrategy == null
                || replicationEventFactory == null
                || resourceResolverFactory == null) {

            String errorMessage = Arrays.toString(new Object[]{name,
                    replicationPackageImporter,
                    replicationPackageExporter,
                    subServiceName,
                    replicationRequestAuthorizationStrategy,
                    queueProvider,
                    queueDistributionStrategy,
                    replicationEventFactory,
                    resourceResolverFactory});
            throw new IllegalArgumentException("all arguments are required: " + errorMessage);
        }

        this.subServiceName = subServiceName;
        this.replicationRequestAuthorizationStrategy = replicationRequestAuthorizationStrategy;
        this.resourceResolverFactory = resourceResolverFactory;
        this.name = name;
        this.passive = passive;
        this.replicationPackageImporter = replicationPackageImporter;
        this.replicationPackageExporter = replicationPackageExporter;
        this.queueProvider = queueProvider;
        this.queueDistributionStrategy = queueDistributionStrategy;
        this.replicationEventFactory = replicationEventFactory;
        this.triggers = triggers == null ? new ArrayList<ReplicationTrigger>() : triggers;
    }

    @Nonnull
    public ReplicationResponse execute(@Nonnull ResourceResolver resourceResolver, @Nonnull ReplicationRequest replicationRequest)
            throws ReplicationAgentException {


        ResourceResolver agentResourceResolver = null;

        try {
            agentResourceResolver = getAgentResourceResolver();

            replicationRequestAuthorizationStrategy.checkPermission(resourceResolver, replicationRequest);

            return scheduleImport(exportPackages(agentResourceResolver, replicationRequest));
        } catch (Exception e) {
            log.error("Error executing replication request {}", replicationRequest, e);
            throw new ReplicationAgentException(e);
        } finally {
            ungetAgentResourceResolver(agentResourceResolver);

        }

    }

    public boolean isPassive() {
        return passive;
    }

    private List<ReplicationPackage> exportPackages(ResourceResolver agentResourceResolver, ReplicationRequest replicationRequest) throws ReplicationPackageBuildingException {
        return replicationPackageExporter.exportPackages(agentResourceResolver, replicationRequest);
    }

    private ReplicationResponse scheduleImport(List<ReplicationPackage> replicationPackages) {
        List<ReplicationResponse> replicationResponses = new LinkedList<ReplicationResponse>();

        for (ReplicationPackage replicationPackage : replicationPackages) {
            replicationResponses.add(schedule(replicationPackage));
        }
        return replicationResponses.size() == 1 ? replicationResponses.get(0) : new CompositeReplicationResponse(replicationResponses);
    }

    private ReplicationResponse schedule(ReplicationPackage replicationPackage) {
        ReplicationResponse replicationResponse;
        log.info("scheduling replication of package {}", replicationPackage);



        // dispatch the replication package to the queue distribution handler
        try {
            boolean success = queueDistributionStrategy.add(replicationPackage, queueProvider);

            Dictionary<Object, Object> properties = new Properties();
            properties.put("replication.package.paths", replicationPackage.getPaths());
            properties.put("replication.agent.name", name);
            replicationEventFactory.generateEvent(ReplicationEventType.PACKAGE_QUEUED, properties);

            replicationResponse = new ReplicationResponse(success? ReplicationQueueItemState.ItemState.QUEUED.toString() :
                    ReplicationQueueItemState.ItemState.ERROR.toString(), success);
        } catch (Exception e) {
            log.error("an error happened during queue processing", e);
            replicationResponse = new ReplicationResponse(e.toString(), false);
        }

        return replicationResponse;
    }

    public Iterable<String> getQueueNames() {
        return queueDistributionStrategy.getQueueNames();
    }

    public ReplicationQueue getQueue(String queueName) throws ReplicationAgentException {
        ReplicationQueue queue;
        try {
            if (queueName != null && queueName.length() > 0) {
                queue = queueProvider.getQueue(queueName);
            } else {
                queue = queueProvider.getQueue(ReplicationQueueDistributionStrategy.DEFAULT_QUEUE_NAME);
            }
        } catch (ReplicationQueueException e) {
            throw new ReplicationAgentException(e);
        }
        return queue;
    }


    public void enable() {
        log.info("enabling agent");

        // register triggers if any
        agentBasedRequestHandler = new AgentBasedRequestHandler(this);

        for (ReplicationTrigger trigger : triggers) {
            try {
                trigger.register(agentBasedRequestHandler);
            } catch (ReplicationTriggerException e) {
                log.error("could not register handler {} from trigger {}", agentBasedRequestHandler, trigger);
            }
        }

        if (!isPassive()) {
            try {
                queueProvider.enableQueueProcessing(new PackageQueueProcessor());
            } catch (ReplicationQueueException e) {
                log.error("cannot enable queue processing", e);
            }
        }
    }

    public void disable() {
        log.info("disabling agent");

        for (ReplicationTrigger trigger : triggers) {
            try {
                trigger.unregister(agentBasedRequestHandler);
            } catch (ReplicationTriggerException e) {
                log.error("could not unregister handler {} from trigger {}", agentBasedRequestHandler, trigger);
            }
        }

        agentBasedRequestHandler = null;

        if (!isPassive()) {

            try {
                queueProvider.disableQueueProcessing();
            } catch (ReplicationQueueException e) {
                log.error("cannot disable queue processing", e);
            }
        }
    }

    private boolean processQueue(String queueName, ReplicationQueueItem queueItem) {
        boolean success = false;
        log.debug("reading package with id {}", queueItem.getId());
        ResourceResolver agentResourceResolver = null;
        try {

            agentResourceResolver = getAgentResourceResolver();

            ReplicationPackage replicationPackage = replicationPackageExporter.getPackage(agentResourceResolver, queueItem.getId());


            if (replicationPackage != null) {
                replicationPackage.getInfo().fillInfo(queueItem.getPackageInfo());

                replicationPackageImporter.importPackage(agentResourceResolver, replicationPackage);

                Dictionary<Object, Object> properties = new Properties();
                properties.put("replication.package.paths", replicationPackage.getPaths());
                properties.put("replication.agent.name", name);
                replicationEventFactory.generateEvent(ReplicationEventType.PACKAGE_REPLICATED, properties);

                if (replicationPackage instanceof SharedReplicationPackage) {
                    ((SharedReplicationPackage) replicationPackage).release(queueName);
                }
                else {
                    replicationPackage.delete();
                }
                success = true;
            } else {
                log.warn("replication package with id {} does not exist", queueItem.getId());
            }

        } catch (ReplicationPackageImportException e) {
            log.error("could not process transport queue", e);
        } catch (LoginException e) {
            log.error("cannot obtain resource resolver", e);
        } finally {
            ungetAgentResourceResolver(agentResourceResolver);
        }
        return success;
    }

    private ResourceResolver getAgentResourceResolver() throws LoginException {
        ResourceResolver resourceResolver;

        Map<String, Object> authenticationInfo = new HashMap<String, Object>();
        authenticationInfo.put(ResourceResolverFactory.SUBSERVICE, subServiceName);
        resourceResolver = resourceResolverFactory.getServiceResourceResolver(authenticationInfo);


        return resourceResolver;
    }

    private void ungetAgentResourceResolver(ResourceResolver resourceResolver) {

        if (resourceResolver != null) {
            try {
                resourceResolver.commit();
            } catch (PersistenceException e) {
                log.error("cannot commit changes to resource resolver", e);
            }
            resourceResolver.close();
        }

    }

    class PackageQueueProcessor implements ReplicationQueueProcessor {
        public boolean process(@Nonnull String queueName, @Nonnull ReplicationQueueItem packageInfo) {
            log.info("running package queue processor for queue {}", queueName);
            return processQueue(queueName, packageInfo);
        }
    }

    public class AgentBasedRequestHandler implements ReplicationRequestHandler {
        private final ReplicationAgent agent;

        public AgentBasedRequestHandler(ReplicationAgent agent) {
            this.agent = agent;
        }

        public void handle(@Nonnull ReplicationRequest request) {
            ResourceResolver agentResourceResolver = null;
            try {
                agentResourceResolver = getAgentResourceResolver();
                agent.execute(agentResourceResolver, request);
            } catch (ReplicationAgentException e) {
                log.error("Error executing handler", e);
            } catch (LoginException e) {
                log.error("Cannot obtain resource resolver");
            } finally {
                ungetAgentResourceResolver(agentResourceResolver);
            }
        }
    }

    private class CompositeReplicationResponse extends ReplicationResponse {

        private boolean successful;

        private String status;

        public CompositeReplicationResponse(List<ReplicationResponse> replicationResponses) {
            super("", false);
            if (replicationResponses.isEmpty()) {
                successful = false;
                status = "empty response";
            } else {
                successful = true;
                StringBuilder statusBuilder = new StringBuilder("[");
                for (ReplicationResponse response : replicationResponses) {
                    successful &= response.isSuccessful();
                    statusBuilder.append(response.getStatus()).append(", ");
                }
                int lof = statusBuilder.lastIndexOf(", ");
                statusBuilder.replace(lof, lof + 2, "]");
                status = statusBuilder.toString();
            }
        }

        @Override
        public boolean isSuccessful() {
            return successful;
        }

        @Override
        public String getStatus() {
            return status;
        }
    }
}
