package bastel_proxy;

import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Forwards the traffic from one web socket to another. The jetty websocket servlet and websocket client
 * require a handler for websocket events. This implements that
 *
 * Implements a Jetty WebSocketListener for the websocket servlet and client forwarding the
 * traffic to the other side:
 * Browser -> Bastel-Proxy ( WebSocketServlet's WebSocketForwarder -> WebSocketClient) -> Target-Site
 * Target-Site -> Bastel-Proxy (WebSocketClient's WebSocketForwarder -> WebSocketServlet) -> Browser
 */
class WebSocketForwarder extends WebSocketAdapter {
    /**
     * Debug information, what direction this instance forwards traffic
     */
    private final String direction;

    /**
     * The peer proxy sending traffic in the opposite direction.
     * This is the {@see WebSocketProxy} wrapper, the actual websocket is in the peerSession
     */
    private WebSocketForwarder peerProxy;
    /**
     * The peer socket sending traffic in the opposite direction
     */
    private final CompletableFuture<Session> peerSession = new CompletableFuture<>();

    WebSocketForwarder(String direction, WebSocketForwarder otherDirection) {
        this.direction = direction;
        this.peerProxy = otherDirection;
    }

    void setPeerProxy(WebSocketForwarder peerProxy) {
        this.peerProxy = peerProxy;
    }

    void connected(Session session) {
        this.peerSession.complete(session);
    }

    private Session forwardTarget() {
        try {
            return this.peerSession.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public void onWebSocketConnect(Session sess) {
        super.onWebSocketConnect(sess);
        assert this.peerProxy != null;
        this.peerProxy.connected(sess);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
        super.onWebSocketClose(statusCode, reason);
        forwardTarget().close(statusCode, reason);
    }


    @Override
    public void onWebSocketError(Throwable cause) {
        onWebSocketClose(500, cause.getMessage());
        cause.printStackTrace();
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len) {
        try {
            super.onWebSocketBinary(payload, offset, len);
            RemoteEndpoint remote = forwardTarget().getRemote();
            remote.sendBytes(ByteBuffer.wrap(payload, offset, len));
        } catch (Throwable e) {
            forwardTarget().close(500, e.getMessage());
        }
    }

    @Override
    public void onWebSocketText(String message) {
        try {
            super.onWebSocketText(message);
            RemoteEndpoint remote = forwardTarget().getRemote();
            remote.sendString(message);
        } catch (Throwable e) {
            forwardTarget().close(500, e.getMessage());
        }
    }
}
