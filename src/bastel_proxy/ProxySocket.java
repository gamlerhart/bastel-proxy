package bastel_proxy;

import clojure.lang.IFn;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A HTTP reverse proxy socket, sending traffic to another https backend.
 * Supports upgrade to websocket
 */
public class ProxySocket extends ProxyServlet.Transparent {
    /**
     * The instance of the WebSocket to use by the Jetty websocket servlet.
     * Because the Jetty websocket servlet wants a factory, we sent the instance we want here and return then in the configured factory
     */
    private final ThreadLocal<WebSocketForwarder> targetProxy = new ThreadLocal<>();
    /**
     * Websocket client, used when fording servlet sockets instead of http traffic
     */
    private final WebSocketClient client = new WebSocketClient(new SslContextFactory.Client());

    /**
     * Rewrite the url passed through the proxy
     */
    private final IFn urlRewrite;

    public ProxySocket(IFn urlRewrite) {
        this.urlRewrite = urlRewrite;
    }


    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String target = rewriteTarget(request);
        if (isWebsocketUpgradeRequest(request, target)) {
            upgradeToWebsocket(request, response, target);
        } else {
            super.service(request, response);
        }
    }

    private void upgradeToWebsocket(HttpServletRequest request, HttpServletResponse response, String target) {
        try {
            ClientUpgradeRequest forward = new ClientUpgradeRequest();
            ServletUpgradeRequest sockReq = new ServletUpgradeRequest(request);
            Map<String, List<String>> headers = new HashMap<>(sockReq.getHeaders());
            removeForbiddenHeaders(headers);
            forward.setHeaders(headers);
            forward.setHeader("Original-Request-Scheme", sockReq.getRequestURI().getScheme());
            forward.setSubProtocols(sockReq.getSubProtocols());
            forward.setCookies(sockReq.getCookies());

            String url = target;
            String schema = new URI(target).getScheme();
            if (schema.equalsIgnoreCase("http")) {
                url = url.replaceFirst("http", "ws");
            } else if (schema.equalsIgnoreCase("https")) {
                url = url.replaceFirst("https", "wss");
            } else {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Endpoint Creation Failed");
                return;
            }

            CompletableFuture<Optional<Throwable>> connectingFuture = new CompletableFuture<>();
            WebSocketForwarder proxyTarget = new WebSocketForwarder("proxy->target", null) {
                @Override
                public void onWebSocketConnect(Session session) {
                    try {
                        super.onWebSocketConnect(session);
                    } catch (Exception e) {
                        throw new RuntimeException(e.getMessage(), e);
                    } finally {
                        connectingFuture.complete(Optional.empty());
                        targetProxy.set(null);
                    }
                }

                @Override
                public void onWebSocketError(Throwable cause) {
                    connectingFuture.complete(Optional.of(cause));
                    super.onWebSocketError(cause);
                }
            };

            WebSocketForwarder clientProxy = new WebSocketForwarder("client->proxy", proxyTarget);
            proxyTarget.setPeerProxy(clientProxy);
            client.connect(proxyTarget, new URI(url), forward);

            Optional<Throwable> connectionError = connectingFuture.get(5, TimeUnit.SECONDS);
            if (connectionError.isPresent()) {
                // Error occurred
                if (connectionError.get() instanceof UpgradeException) {
                    UpgradeException u = (UpgradeException) connectionError.get();
                    try {
                        response.sendError(u.getResponseStatusCode(), u.getMessage());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    response.sendError(500, connectionError.get().getMessage());
                    connectionError.get().printStackTrace();
                }
            } else {
                try {
                    targetProxy.set(clientProxy);
                    webSockets.service(request, response);
                } finally {
                    targetProxy.set(null);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private void removeForbiddenHeaders(Map<String, List<String>> headers) {
        // Avoid duplicate, since the client will add this header
        // See ClientUpgradeRequest.FORBIDDEN_HEADERS
        headers.remove("Upgrade");
        headers.remove("Sec-WebSocket-Version");
        headers.remove("Sec-WebSocket-Key");
        headers.remove("Pragma");
        headers.remove("Cache-Control");
    }

    private boolean isWebsocketUpgradeRequest(HttpServletRequest request, String url) {
        return url != null && request.getMethod().equals("GET") && "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        webSockets.init(config);
        try {
            client.start();
        } catch (Exception e) {
            throw new ServletException(e.getMessage(), e);
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            client.stop();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    protected void addProxyHeaders(HttpServletRequest clientRequest, Request proxyRequest) {
        super.addProxyHeaders(clientRequest, proxyRequest);
        String schema = clientRequest.getRequestURL().toString().split(":")[0];
        proxyRequest.header("Original-Request-Scheme", schema);
    }

    @Override
    protected void onProxyResponseSuccess(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse) {
        // Fix issues handling HEAD request.
        // If we don't flush, it just doesn't send the answer back?
        try {
            proxyResponse.flushBuffer();
        } catch (IOException e) {
            e.printStackTrace();
        }
        super.onProxyResponseSuccess(clientRequest, proxyResponse, serverResponse);
    }

    @Override
    protected String rewriteTarget(HttpServletRequest request) {
        String target =  super.rewriteTarget(request);
        String finalTarget = (String)urlRewrite.invoke(target);
        return finalTarget;
    }

    private final WebSocketServlet webSockets = new WebSocketServlet() {
        @Override
        public void configure(WebSocketServletFactory factory) {
            factory.setCreator((servletUpgradeRequest, servletUpgradeResponse) -> {

                try {
                    WebSocketForwarder clientProxy = targetProxy.get();
                    // Doggy: Should actually wait for the response from forwarded target and use that response
                    // servletUpgradeResponse.setHeader("Sec-WebSocket-Protocol");
                    if (!servletUpgradeRequest.getSubProtocols().isEmpty()) {
                        servletUpgradeResponse.setAcceptedSubProtocol(
                                servletUpgradeRequest.getSubProtocols().get(0));
                    }
                    return clientProxy;
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            });
        }
    };

    @Override
    protected HttpClient newHttpClient() {
        int selectors = Math.max(1, ProcessorUtils.availableProcessors() / 2);
        return new HttpClient(new HttpClientTransportOverHTTP(selectors), new SslContextFactory.Client());
    }
}
