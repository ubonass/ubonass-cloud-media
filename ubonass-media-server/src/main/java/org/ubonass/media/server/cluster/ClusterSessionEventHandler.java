package org.ubonass.media.server.cluster;

import lombok.Data;
import org.ubonass.media.server.cluster.event.Event;
import org.ubonass.media.server.cluster.event.SdpEvent;
import org.ubonass.media.server.core.MediaSessionManager;
import org.ubonass.media.server.kurento.core.KurentoParticipant;

import java.io.Serializable;
import java.util.concurrent.Callable;

@Data
public class ClusterSessionEventHandler implements Callable<Event>, Runnable, Serializable {

    private Event event;

    public ClusterSessionEventHandler(Event event) {
        this.event = event;
    }

    @Override
    public void run() {
        ClusterRpcService clusterRpcService = ClusterRpcService.getContext();
        MediaSessionManager sessionManager = clusterRpcService.getSessionManager();
        switch (event.getEventType()) {
            case Event.MEDIA_EVENT_CLOSE_SESSION:
                sessionManager.closeSession(event.getSessionId(), null);
                break;
            default:
                break;
        }
    }

    @Override
    public Event call() throws Exception {
        ClusterRpcService clusterRpcService = ClusterRpcService.getContext();
        MediaSessionManager sessionManager = clusterRpcService.getSessionManager();
        ClusterConnection connection =
                clusterRpcService.getConnection(event.getSessionId(), event.getParticipantPublicId());
        KurentoParticipant kParticipant =
                (KurentoParticipant)
                        sessionManager.getParticipant(event.getSessionId(), connection.getParticipantPrivateId());
        Event result = null;
        if (event instanceof SdpEvent) {
            if (event.getEventType().equals(Event.MEDIA_EVENT_PROCESS_SDPOFFER)) {
                String sdpOffer = ((SdpEvent) event).getSdpOffer();
                String sdpAnswer =
                        kParticipant.getRemotePublisher().getEndpoint().processOffer(sdpOffer);
                result = new SdpEvent(event.getParticipantPublicId(),event.getSessionId(),
                        event.getEventType(), sdpAnswer, null);

            }
        }
        return result;
    }
}
