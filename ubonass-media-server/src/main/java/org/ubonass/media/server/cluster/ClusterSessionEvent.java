package org.ubonass.media.server.cluster;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.server.cluster.event.Event;
import org.ubonass.media.server.cluster.event.SdpEvent;
import org.ubonass.media.server.cluster.event.SessionEvent;
import org.ubonass.media.server.kurento.endpoint.RemoteEndpoint;
import org.ubonass.media.server.kurento.endpoint.SdpType;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class ClusterSessionEvent {

    private static final Logger logger = LoggerFactory.getLogger(ClusterSessionEvent.class);

    @Autowired
    private ClusterRpcService clusterRpcService;

    /**
     * @param sessionId 房间号
     * @param participantPublicId:目标参与者的participantPublicId
     * @param remotePublisher
     */
    public void publishToRoom(String sessionId, String participantPublicId, RemoteEndpoint remotePublisher) {


        String sdpOffer = remotePublisher.prepareRemoteConnection();

        Event sdpEvent =
                new SdpEvent(participantPublicId, sessionId,
                        Event.MEDIA_EVENT_PROCESS_SDPOFFER,null,sdpOffer);

        Callable callable = new ClusterSessionEventHandler(sdpEvent);
        Future<Event> processAnswer = (Future<Event>)
                clusterRpcService.submitTaskToMembers(callable,
                        clusterRpcService.getConnectionMemberId(sessionId,participantPublicId));
        try {
            Event result = processAnswer.get();
            if (result instanceof SdpEvent) {
                logger.info("rtpAnswer {}", ((SdpEvent) result).getSdpAnswer());
                remotePublisher.startProcessOfferOrAnswer(SdpType.ANSWER,
                        ((SdpEvent) result).getSdpAnswer());
                /*if (receiveEnable)
                    remotePublisher.getEndpoint().connect(publisher.getEndpoint());*/
            }
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
    }

    /**
     *
     * @param sessionId
     * @param participantPublicId:目标参与者的participantPublicId
     */
    public void closeSession(String sessionId, String participantPublicId) {

        Event sessionEvent =
                new SessionEvent(participantPublicId,
                        sessionId,Event.MEDIA_EVENT_CLOSE_SESSION);

        Runnable runnable = new ClusterSessionEventHandler(sessionEvent);
        clusterRpcService.executeToMember(runnable,
                clusterRpcService.getConnectionMemberId(sessionId,participantPublicId));
    }

}
