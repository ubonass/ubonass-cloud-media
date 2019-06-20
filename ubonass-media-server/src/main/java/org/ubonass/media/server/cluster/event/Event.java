package org.ubonass.media.server.cluster.event;

import lombok.Data;

import java.io.Serializable;

@Data
public  class Event implements Serializable {

    public static final String MEDIA_EVENT_PROCESS_SDPOFFER = "sdpOffer";
    public static final String MEDIA_EVENT_CLOSE_SESSION = "closeSession";

    private String participantPublicId;
    private String sessionId;
    private String eventType;

    public Event(String participantPublicId, String sessionId, String eventType) {
        this.participantPublicId = participantPublicId;
        this.sessionId = sessionId;
        this.eventType = eventType;
    }
}

