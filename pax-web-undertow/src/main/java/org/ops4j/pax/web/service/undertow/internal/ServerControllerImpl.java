package org.ops4j.pax.web.service.undertow.internal;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.handlers.PathHandler;
import org.ops4j.lang.NullArgumentException;
import org.ops4j.pax.web.service.spi.Configuration;
import org.ops4j.pax.web.service.spi.LifeCycle;
import org.ops4j.pax.web.service.spi.ServerController;
import org.ops4j.pax.web.service.spi.ServerEvent;
import org.ops4j.pax.web.service.spi.ServerListener;
import org.ops4j.pax.web.service.spi.model.ContainerInitializerModel;
import org.ops4j.pax.web.service.spi.model.ContextModel;
import org.ops4j.pax.web.service.spi.model.ErrorPageModel;
import org.ops4j.pax.web.service.spi.model.EventListenerModel;
import org.ops4j.pax.web.service.spi.model.FilterModel;
import org.ops4j.pax.web.service.spi.model.Model;
import org.ops4j.pax.web.service.spi.model.SecurityConstraintMappingModel;
import org.ops4j.pax.web.service.spi.model.ServerModel;
import org.ops4j.pax.web.service.spi.model.ServletModel;
import org.ops4j.pax.web.service.spi.model.WelcomeFileModel;
import org.osgi.service.http.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Guillaume Nodet
 */
public class ServerControllerImpl implements ServerController {

    private enum State {
        Unconfigured, Stopped, Started
    }

    private static final Logger LOG = LoggerFactory.getLogger(ServerControllerImpl.class);

    private Configuration configuration;
    private final Set<ServerListener> listeners = new CopyOnWriteArraySet<>();
    private State state = State.Unconfigured;

    private final PathHandler path = Handlers.path();
    private Undertow server;

    private final ConcurrentMap<HttpContext, Context> contextMap = new ConcurrentHashMap<>();

    public ServerControllerImpl(ServerModel serverModel) {
    }

    @Override
    public synchronized void start() {
        LOG.debug("Starting server [{}]", this);
        assertState(State.Stopped);
        doStart();
        state = State.Started;
        notifyListeners(ServerEvent.STARTED);
    }

    @Override
    public synchronized void stop() {
        LOG.debug("Stopping server [{}]", this);
        assertNotState(State.Unconfigured);
        if (state == State.Started) {
            doStop();
            state = State.Stopped;
        }
        notifyListeners(ServerEvent.STOPPED);
    }

    @Override
    public synchronized void configure(final Configuration config) {
        LOG.debug("Configuring server [{}] -> [{}] ", this, config);
        if (config == null) {
            throw new IllegalArgumentException("configuration == null");
        }
        configuration = config;
        switch (state) {
        case Unconfigured:
            state = State.Stopped;
            notifyListeners(ServerEvent.CONFIGURED);
            break;
        case Started:
            doStop();
            doStart();
            break;
        }
    }

    @Override
    public void addListener(ServerListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener == null");
        }
        listeners.add(listener);
    }

    @Override
    public void removeListener(ServerListener listener) {
        listeners.remove(listener);
    }

    @Override
    public synchronized boolean isStarted() {
        return state == State.Started;
    }

    @Override
    public synchronized boolean isConfigured() {
        return state != State.Unconfigured;
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public synchronized Integer getHttpPort() {
        Configuration config = configuration;
        if (config == null) {
            throw new IllegalStateException("Not configured");
        }
        return config.getHttpPort();
    }

    @Override
    public synchronized Integer getHttpSecurePort() {
        Configuration config = configuration;
        if (config == null) {
            throw new IllegalStateException("Not configured");
        }
        return config.getHttpSecurePort();
    }

    void notifyListeners(ServerEvent event) {
        for (ServerListener listener : listeners) {
            listener.stateChanged(event);
        }
    }

    void doStart() {
        Undertow.Builder builder = Undertow.builder();
        for (String address : configuration.getListeningAddresses()) {
            if (configuration.isHttpEnabled()) {
                builder.addHttpListener(configuration.getHttpPort(), address);
            }
            if (configuration.isHttpSecureEnabled()) {
                // TODO: ssl config
//                SSLContext context = SSLContext.getInstance("TLS");
//                context.init();
//                configuration.getSslKeystore();
//                configuration.getSslKeyPassword();
//                configuration.getSslKeystoreType();
//                configuration.getSslPassword();
//                builder.addHttpsListener(configuration.getHttpSecurePort(), address, context);
            }
        }
        builder.setHandler(path);
        server = builder.build();
        server.start();
    }

    void doStop() {
        server.stop();
    }

    @Override
    public synchronized LifeCycle getContext(ContextModel model) {
        assertState(State.Started);
        return findOrCreateContext(model);
    }

    @Override
    public synchronized void removeContext(HttpContext httpContext) {
        assertNotState(State.Unconfigured);
        final Context context = contextMap.remove(httpContext);
        if (context == null) {
            throw new IllegalStateException("Cannot remove the context because it does not exist: " + httpContext);
        }
        context.destroy();
    }

    private void assertState(State state) {
        if (this.state != state) {
            throw new IllegalStateException("State is " + this.state + " but should be " + state);
        }
    }

    private void assertNotState(State state) {
        if (this.state == state) {
            throw new IllegalStateException("State should not be " + this.state);
        }
    }

    private Context findContext(final ContextModel contextModel) {
        NullArgumentException.validateNotNull(contextModel, "contextModel");
        HttpContext httpContext = contextModel.getHttpContext();
        return contextMap.get(httpContext);
    }

    private Context findOrCreateContext(final ContextModel contextModel) {
        NullArgumentException.validateNotNull(contextModel, "contextModel");
        Context newCtx = new Context(path, contextModel);
        Context oldCtx = contextMap.putIfAbsent(contextModel.getHttpContext(), newCtx);
        return oldCtx != null ? oldCtx : newCtx;
    }

    @Override
    public synchronized void addServlet(ServletModel model) {
        assertNotState(State.Unconfigured);
        try {
            final Context context = findOrCreateContext(model.getContextModel());
            context.addServlet(model);
        } catch (ServletException e) {
            throw new RuntimeException("Unable to add servlet", e);
        }
    }

    @Override
    public void removeServlet(ServletModel model) {
        assertState(State.Started);
        try {
            final Context context = findContext(model.getContextModel());
            if (context != null) {
                context.removeServlet(model);
            }
        } catch (ServletException e) {
            throw new RuntimeException("Unable to remove servlet", e);
        }
    }

    @Override
    public void addEventListener(EventListenerModel eventListenerModel) {

    }

    @Override
    public void removeEventListener(EventListenerModel eventListenerModel) {

    }

    @Override
    public void addFilter(FilterModel filterModel) {

    }

    @Override
    public void removeFilter(FilterModel filterModel) {

    }

    @Override
    public void addErrorPage(ErrorPageModel model) {

    }

    @Override
    public void removeErrorPage(ErrorPageModel model) {

    }

    @Override
    public void addWelcomFiles(WelcomeFileModel model) {

    }

    @Override
    public void removeWelcomeFiles(WelcomeFileModel model) {

    }

    @Override
    public Servlet createResourceServlet(ContextModel contextModel, String alias, String name) {
        return new ResourceServlet(contextModel.getHttpContext());
    }

    @Override
    public void addSecurityConstraintMapping(SecurityConstraintMappingModel secMapModel) {

    }

    @Override
    public void addContainerInitializerModel(ContainerInitializerModel model) {

    }
}
