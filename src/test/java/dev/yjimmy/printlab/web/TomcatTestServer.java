package dev.yjimmy.printlab.web;

import jakarta.servlet.http.HttpServlet;
import java.net.URI;
import java.nio.file.Path;
import org.apache.catalina.Context;
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
