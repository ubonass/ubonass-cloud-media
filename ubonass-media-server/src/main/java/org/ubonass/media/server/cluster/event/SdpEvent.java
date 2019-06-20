package org.ubonass.media.server.cluster.event;

import lombok.Data;

@Data
public class SdpEvent extends Event{

    private static final long serialVersionUID = 6908757203236929725L;

    String sdpAnswer;
    String sdpOffer;

    public SdpEvent(String participantPublicId,
                    String sessionId, String eventType,
                    String sdpAnswer, String sdpOffer) {
        super(participantPublicId, sessionId, eventType);
        this.sdpAnswer = sdpAnswer;
        this.sdpOffer = sdpOffer;
    }

}
