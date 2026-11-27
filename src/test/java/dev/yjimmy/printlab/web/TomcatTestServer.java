package dev.yjimmy.printlab.web;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.http.HttpServlet;
import java.net.URI;
import java.nio.file.Path;
import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

/**
 * An embedded Tomcat on a random free port, for testing servlets over real HTTP. Register
 * servlets, call {@link #start()}, and close it when done.
 */
final class TomcatTestServer implements AutoCloseable {

    private final Tomcat tomcat = new Tomcat();
    private final Context context;

    TomcatTestServer(Path baseDir) {
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        context = tomcat.addContext("", baseDir.toString());
    }

    TomcatTestServer servlet(String name, HttpServlet servlet, String urlPattern) {
        Tomcat.addServlet(context, name, servlet);
        context.addServletMappingDecoded(urlPattern, name);
        return this;
    }

    /**
     * Adds a context listener, called as the container would call it: {@code contextInitialized}
     * once the context has started, {@code contextDestroyed} before it stops. Tomcat only takes
     * listener class names, and the listeners under test need constructor arguments, so this
     * drives them from the context's lifecycle events instead.
     */
    TomcatTestServer listener(ServletContextListener listener) {
        context.addLifecycleListener(event -> {
            if (event.getLifecycle() != context) {
                return;
            }
            if (Lifecycle.AFTER_START_EVENT.equals(event.getType())) {
                listener.contextInitialized(new ServletContextEvent(context.getServletContext()));
            } else if (Lifecycle.BEFORE_STOP_EVENT.equals(event.getType())) {
                listener.contextDestroyed(new ServletContextEvent(context.getServletContext()));
            }
        });
        return this;
    }

    TomcatTestServer start() throws LifecycleException {
        tomcat.start();
        return this;
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + tomcat.getConnector().getLocalPort() + path);
    }

    @Override
    public void close() throws LifecycleException {
        tomcat.stop();
        tomcat.destroy();
    }
}
