/*
 * Copyright 2012-2016, the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.flipkart.flux.impl.task.registry;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Address;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.cluster.routing.ClusterRouterPool;
import akka.cluster.routing.ClusterRouterPoolSettings;
import akka.cluster.singleton.ClusterSingletonManager;
import akka.cluster.singleton.ClusterSingletonManagerSettings;
import akka.cluster.singleton.ClusterSingletonProxy;
import akka.cluster.singleton.ClusterSingletonProxySettings;
import akka.remote.routing.RemoteRouterConfig;
import akka.routing.RoundRobinPool;
import com.flipkart.flux.domain.FluxError;
import com.flipkart.flux.impl.boot.ActorSystemManager;
import com.flipkart.flux.impl.task.CustomSuperviseStrategy;
import com.flipkart.flux.impl.temp.Worker;
import com.flipkart.polyguice.core.Initializable;
import javafx.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Eagerly creates and maintains references to all the required routers of the system
 * @see RouterRegistry
 * @author yogesh.nachnani
 */
@Singleton
public class EagerInitRouterRegistryImpl implements RouterRegistry, Initializable {

	/** Logger for this class*/
	private static final Logger LOGGER = LoggerFactory.getLogger(EagerInitRouterRegistryImpl.class);

	/** Access to the Actor system initialized by Flux*/
    private ActorSystemManager actorSystemManager;

    /** Configuration access for Router setup*/
    private RouterConfigurationRegistry routerConfigurationRegistry;

    private final CustomSuperviseStrategy superviseStrategy;

    /** Local Map of initialized Router instances*/
    private final HashMap<String, ActorRef> proxyMap;

    /** Mutating list of Akka cluster members. Using an expensive list implementation for concurrent access but with low update rate */
    private List<Address> memberAddresses = new CopyOnWriteArrayList<>();

    @Inject
    public EagerInitRouterRegistryImpl(ActorSystemManager actorSystemManager, RouterConfigurationRegistry routerConfigurationRegistry,
                                       CustomSuperviseStrategy superviseStrategy) {
        this.proxyMap = new HashMap<>();
        this.actorSystemManager = actorSystemManager;
        this.routerConfigurationRegistry = routerConfigurationRegistry;
        this.superviseStrategy = superviseStrategy;
    }

    /**
     * This implementation looks up the local hashmap and returns the appropriate router
     * @param forWorker the (convention based) name for the router
     * @return ActorRef for the router or null if the router does not exist
     */
    @Override
    public ActorRef getRouter(String forWorker) {
        return this.proxyMap.get(forWorker);
    }

    /**
     * Iterates over given list of configured router names and creates a cluster singleton router for each
     *
     * */
    @Override
    public void initialize() {
        final ActorSystem actorSystem = actorSystemManager.retrieveActorSystem();
        ClusterSingletonManagerSettings settings = ClusterSingletonManagerSettings.create(actorSystem);
        // setup the ClusterListener Actor
        actorSystem.actorOf(Props.create(ClusterListener.class, this.memberAddresses), "clusterListener");
        while (this.memberAddresses.isEmpty()) {
        	LOGGER.info("Router init waiting 1000ms for cluster members to join..." );
        	try {
				Thread.sleep(1000L);
			} catch (InterruptedException e) {
				throw new FluxError(FluxError.ErrorType.runtime, "All Router(s) initialization failed.", e);
			}
        }
        final Iterable<Pair<String, ClusterRouterPoolSettings>> configurations = routerConfigurationRegistry.getConfigurations();

        for (Pair<String, ClusterRouterPoolSettings> next : configurations) {
            actorSystem.actorOf(
                ClusterSingletonManager.props(new ClusterRouterPool(new RoundRobinPool(2).withSupervisorStrategy(superviseStrategy.getStrategy()), next.getValue()).props(
                    new RemoteRouterConfig(new RoundRobinPool(6), this.memberAddresses).props(
                        Props.create(Worker.class))), PoisonPill.getInstance(), settings), next.getKey());

            ClusterSingletonProxySettings proxySettings = ClusterSingletonProxySettings.create(actorSystem);

            this.proxyMap.put(next.getKey(), actorSystem.actorOf(ClusterSingletonProxy.props("/user/" + next.getKey(),
                proxySettings), next.getKey() + "_routerProxy"));
        }

    }
}
