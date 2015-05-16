package org.ops4j.pax.web.service.undertow.internal;

import java.io.IOException;
import java.net.URL;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.resource.Resource;
import io.undertow.server.handlers.resource.ResourceChangeListener;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.server.handlers.resource.ResourceManager;
import io.undertow.server.handlers.resource.URLResource;
import io.undertow.servlet.spec.HttpServletRequestImpl;
import org.osgi.service.http.HttpContext;

/**
 * can be based on org.apache.catalina.servlets.DefaultServlet
 *
 * @author Romain Gilles Date: 7/26/12 Time: 10:41 AM
 */
public class ResourceServlet extends HttpServlet implements ResourceManager {

    private final HttpContext context;
    private final HttpHandler handler;

    public ResourceServlet(final HttpContext httpContext) {

        this.context = httpContext;
        this.handler = new ResourceHandler(this);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse resp) throws ServletException, IOException {
        if (!(request instanceof HttpServletRequestImpl)) {
            throw new IllegalStateException("Request is not an instance of " + HttpServletRequestImpl.class.getName());
        }
        HttpServerExchange exchange = ((HttpServletRequestImpl) request).getExchange();
        try {
            handler.handleRequest(exchange);
        } catch (IOException | ServletException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    @Override
    public Resource getResource(String path) throws IOException {
        URL url = context.getResource(path);
        return url != null ? new URLResource(url, url.openConnection(), path) : null;
    }

    @Override
    public boolean isResourceChangeListenerSupported() {
        return false;
    }

    @Override
    public void registerResourceChangeListener(ResourceChangeListener listener) {

    }

    @Override
    public void removeResourceChangeListener(ResourceChangeListener listener) {

    }

    @Override
    public void close() throws IOException {

    }

}
