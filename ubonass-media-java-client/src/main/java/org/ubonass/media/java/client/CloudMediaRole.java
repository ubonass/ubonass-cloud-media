package org.ubonass.media.java.client;


/**
 * See {@link org.ubonass.media.java.client.TokenOptions.Builder#role(CloudMediaRole)}
 */
public enum CloudMediaRole {
    /**
     * Can subscribe to published Streams of other users
     */
    SUBSCRIBER,

    /**
     * SUBSCRIBER permissions + can publish their own Streams (call
     * <code>Session.publish()</code>)
     */
    PUBLISHER,

    /**
     * SUBSCRIBER + PUBLISHER permissions + can force the unpublishing or
     * disconnection over a third-party Stream or Connection (call
     * <code>Session.forceUnpublish()</code> and
     * <code>Session.forceDisconnect()</code>)
     */
    MODERATOR;
}
