package org.ubonass.media.server.cluster.event;

public class SessionEvent extends Event{

    private static final long serialVersionUID = -7565325893322981165L;

    public SessionEvent(String participantPublicId, String sessionId, String eventType) {
        super(participantPublicId, sessionId, eventType);
    }
}
