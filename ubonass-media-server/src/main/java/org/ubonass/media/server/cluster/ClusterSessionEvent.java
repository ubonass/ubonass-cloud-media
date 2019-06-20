package org.ubonass.media.server.cluster;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.server.kurento.endpoint.RemoteEndpoint;
import org.ubonass.media.server.kurento.endpoint.SdpType;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class ClusterSessionEvent {

    private static final Logger logger = LoggerFactory.getLogger(ClusterSessionEvent.class);

        /*{
        "event":"sdpOfferProcess",
        "params":"{
            "sdpOffer":"sdpOffer"
        }"

      {
        "event":"sdpOfferProcess",
        "sdpAnswer":"xxxxx"
      }
    }*/

    public static final String REMOTE_MEDIA_EVENT = "event";
    public static final String REMOTE_MEDIA_EVENT_SDPOFFER_PROCESS = "sdpOfferProcess";
    public static final String REMOTE_MEDIA_EVENT_CLOSE_SESSION = "closeSession";
    public static final String REMOTE_MEDIA_PARAMS_SDPOFFER = "sdpOffer";
    public static final String REMOTE_MEDIA_PARAMS_SDPANSWER = "sdpAnswer";

    @Autowired
    private ClusterRpcService clusterRpcService;

    /**
     * @param sessionId 房间号
     * @param participantPublicId:目标参与者的participantPublicId
     * @param remotePublisher
     */
    public void publishToRoom(String sessionId, String participantPublicId, RemoteEndpoint remotePublisher) {
        JsonObject object = new JsonObject();
        object.addProperty(REMOTE_MEDIA_EVENT, REMOTE_MEDIA_EVENT_SDPOFFER_PROCESS);
        JsonObject paramsObject = new JsonObject();
        paramsObject.addProperty(REMOTE_MEDIA_PARAMS_SDPOFFER, remotePublisher.prepareRemoteConnection());
        object.addProperty("params", paramsObject.toString());
        String message = object.toString();

        Callable callable = new ClusterSessionEventHandler(
                sessionId, participantPublicId, message);

        Future<String> processAnswer = (Future<String>)
                clusterRpcService.submitTaskToMembers(callable,
                        clusterRpcService.getConnectionMemberId(sessionId,participantPublicId));
        try {
            JsonParser parser = new JsonParser();
            JsonObject rtpAnswerObject =
                    (JsonObject) parser.parse(processAnswer.get());
            if (rtpAnswerObject.
                    get(REMOTE_MEDIA_EVENT).toString().equals(REMOTE_MEDIA_EVENT_SDPOFFER_PROCESS)) {
                String rtpAnswer = rtpAnswerObject.get(REMOTE_MEDIA_PARAMS_SDPANSWER).toString();
                logger.info("rtpAnswer {}", rtpAnswer);
                remotePublisher.startProcessOfferOrAnswer(SdpType.ANSWER, rtpAnswer);
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
        JsonObject object = new JsonObject();
        object.addProperty(REMOTE_MEDIA_EVENT, REMOTE_MEDIA_EVENT_CLOSE_SESSION);
        String message = object.toString();

        Runnable runnable = new ClusterSessionEventHandler(
                sessionId, participantPublicId, message);
        clusterRpcService.executeToMember(runnable,
                clusterRpcService.getConnectionMemberId(sessionId,participantPublicId));
    }

}
