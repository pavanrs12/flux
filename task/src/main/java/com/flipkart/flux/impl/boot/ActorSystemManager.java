package com.flipkart.flux.impl.boot;

import akka.actor.ActorSystem;
import com.flipkart.polyguice.core.Disposable;
import com.flipkart.polyguice.core.Initializable;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import kamon.Kamon;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Responsible for bringing up the entire akka runtime
 * Lifecycle of this class is managed using polyguice
 * @author yogesh.nachnani
 */
//TODO the lifecycle will be managed by the polyguice container
@Singleton
public class ActorSystemManager implements Disposable, Initializable {

    private String actorSystemName;
    private String configName;
    private  Boolean withMetrics;

    private ActorSystem system;

    private boolean isInitialised;

    ActorSystemManager() {
        this.isInitialised = false;
    }

    @Inject
    public ActorSystemManager(@Named("runtime.actorsystem.name") String actorSystemName,
                              @Named("runtime.actorsystem.configname") String configName,
                              @Named("runtime.actorsystem.metrics") Boolean withMetrics
    ) {
        this();
        this.actorSystemName = actorSystemName;
        this.configName = configName;
        this.withMetrics = withMetrics;
    }

    public ActorSystem retrieveActorSystem() {
        if(isInitialised) {
            return this.system;
        }
        throw new IllegalStateException("Actor system not initialised yet");
    }

    /**
     * Reads the configurations and joins/creates the akka cluster
     */
    @Override
    public void initialize() {
        if(withMetrics) {
            Kamon.start();
        }
        Config config = ConfigFactory.load(configName);
        system = ActorSystem.create(actorSystemName, config);
        this.isInitialised = true;
    }

    @Override
    public void dispose() {
        if(withMetrics) {
            Kamon.shutdown();
        }
        if (system != null) {
            system.shutdown();
        }
    }
}