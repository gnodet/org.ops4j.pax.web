package org.ops4j.pax.web.service.undertow.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.util.ImmediateInstanceFactory;
import io.undertow.util.StatusCodes;
import org.ops4j.pax.web.service.WebContainerConstants;
import org.ops4j.pax.web.service.spi.LifeCycle;
import org.ops4j.pax.web.service.spi.model.ContextModel;
import org.ops4j.pax.web.service.spi.model.ServletModel;

/**
 * @author Guillaume Nodet
 */
public class Context implements LifeCycle, HttpHandler {

    private final PathHandler path;
    private final ContextModel contextModel;
    private final List<ServletModel> servlets = new ArrayList<>();
    private final ServletContainer container = ServletContainer.Factory.newInstance();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile HttpHandler handler;

    public Context(PathHandler path, ContextModel contextModel) {
        this.path = path;
        this.contextModel = contextModel;
    }

    @Override
    public synchronized void start() throws Exception {
        if (started.compareAndSet(false, true)) {
            for (ServletModel servlet : servlets) {
                doStart(servlet);
            }
        }
    }

    @Override
    public synchronized void stop() throws Exception {
        if (started.compareAndSet(true, false)) {
            for (ServletModel servlet : servlets) {
                doStop(servlet);
            }
        }
    }

    private void doStart(ServletModel servlet) throws ServletException {
        for (String pattern : servlet.getUrlPatterns()) {
            if (pattern.endsWith("/*")) {
                pattern = pattern.substring(0, pattern.length() - 2);
                path.addPrefixPath(pattern, this);
            } else {
                throw new ServletException("Servlet mapping does not end with /*: " + pattern);
            }
        }
    }

    private void doStop(ServletModel servlet) throws ServletException {
        for (String pattern : servlet.getUrlPatterns()) {
            if (pattern.endsWith("/*")) {
                pattern = pattern.substring(0, pattern.length() - 2);
                path.removePrefixPath(pattern);
            } else {
                throw new ServletException("Servlet mapping does not end with /*: " + pattern);
            }
        }
    }

    public synchronized void destroy() {

    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        HttpHandler h = getHandler();
        if (h != null) {
            // Put back original request path
            exchange.setRelativePath(exchange.getRequestPath());
            h.handleRequest(exchange);
        } else {
            exchange.setResponseCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
        }
    }

    private HttpHandler getHandler() throws ServletException {
        if (handler == null) {
            synchronized (this) {
                if (handler == null) {
                    handler = createHandler();
                }
            }
        }
        return handler;
    }

    private HttpHandler createHandler() throws ServletException {
        DeploymentInfo deployment = new DeploymentInfo();
        deployment.setDeploymentName(contextModel.getContextName());
        deployment.setContextPath("/");
        deployment.setClassLoader(contextModel.getClassLoader());
        deployment.addServletContextAttribute(WebContainerConstants.BUNDLE_CONTEXT_ATTRIBUTE, contextModel.getBundle().getBundleContext());
        for (ServletModel servlet : servlets) {
            ServletInfo info;
            Servlet instance = servlet.getServlet();
            if (instance != null) {
                info = Servlets.servlet(servlet.getName(), instance.getClass(), new ImmediateInstanceFactory<>(instance));
            } else {
                info = Servlets.servlet(servlet.getName(), servlet.getServletClass());
            }
            info.addMappings(servlet.getUrlPatterns());
            info.setAsyncSupported(true);
            deployment.addServlet(info);
        }
        DeploymentManager manager = container.addDeployment(deployment);
        manager.deploy();
        return manager.start();
    }

    public synchronized void addServlet(ServletModel model) throws ServletException {
        servlets.add(model);
        if (started.get()) {
            doStart(model);
            handler = null;
        }
    }

    public synchronized void removeServlet(ServletModel model) throws ServletException {
        if (servlets.remove(model)) {
            if (started.get()) {
                doStop(model);
                handler = null;
            }
        }
    }
}
