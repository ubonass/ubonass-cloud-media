package org.ubonass.media.server.kurento.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Data;
import org.ubonass.media.server.cluster.ClusterConnection;
import org.ubonass.media.server.cluster.ClusterRpcService;
import org.ubonass.media.server.core.MediaSessionManager;

import java.io.Serializable;
import java.util.concurrent.Callable;

@Data
public class KurentoMediaRemoteSession implements Callable<String>, Runnable, Serializable {
    public static final String REMOTE_MEDIA_EVENT = "event";
    public static final String REMOTE_MEDIA_EVENT_SDPOFFER_PROCESS = "sdpOfferProcess";
    public static final String REMOTE_MEDIA_EVENT_CLOSE_SESSION = "closeSession";
    public static final String REMOTE_MEDIA_PARAMS_SDPOFFER = "sdpOffer";
    public static final String REMOTE_MEDIA_PARAMS_SDPANSWER = "sdpAnswer";
    private String participantPublicId;
    private String message;
    private String sessionId;

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
    public KurentoMediaRemoteSession(
            String sessionId, String participantPublicId, String message) {
        this.sessionId = sessionId;
        this.participantPublicId = participantPublicId;
        this.message = message;
    }

    private JsonObject messageJsonObject() {
        if (message != null) {
            JsonParser jsonParser = new JsonParser();
            JsonElement parse = jsonParser.parse(message);
            return parse.getAsJsonObject();
        }
        return null;
    }

    @Override
    public void run() {
        if (sessionId == null
                || participantPublicId == null) return;
        JsonObject messageObject = messageJsonObject();
        if (messageObject == null) return;
        ClusterRpcService clusterRpcService = ClusterRpcService.getContext();
        MediaSessionManager sessionManager = clusterRpcService.getSessionManager();
        switch (messageObject.get(REMOTE_MEDIA_EVENT).toString()) {
            case REMOTE_MEDIA_EVENT_CLOSE_SESSION:
                sessionManager.closeSession(sessionId, null);
                break;
            default:
                break;
        }
    }

    @Override
    public String call() throws Exception {
        if (sessionId == null
                || participantPublicId == null) return null;
        JsonObject messageObject = messageJsonObject();
        if (messageObject == null) return null;
        ClusterRpcService clusterRpcService = ClusterRpcService.getContext();
        MediaSessionManager sessionManager = clusterRpcService.getSessionManager();
        JsonObject paramsObject = null;
        if (messageObject.has("params"))
            paramsObject = messageObject.getAsJsonObject("params");

        String result = null;
        switch (messageObject.get(REMOTE_MEDIA_EVENT).toString()) {
            case REMOTE_MEDIA_EVENT_SDPOFFER_PROCESS:
                if (paramsObject == null) break;
                String sdpOffer = paramsObject.get(REMOTE_MEDIA_PARAMS_SDPOFFER).toString();
                if (clusterRpcService.getSessionsMap()
                        .containsKey(sessionId)) {
                    ClusterConnection connection =
                            clusterRpcService.getConnection(sessionId, participantPublicId);
                    KurentoParticipant kParticipant =
                            (KurentoParticipant)
                                    sessionManager.getParticipant(sessionId, connection.getParticipantPrivateId());
                    String sdpAnswer =
                            kParticipant.getRemoteEndpoint().getEndpoint().processOffer(sdpOffer);
                    JsonObject object = new JsonObject();
                    object.addProperty(REMOTE_MEDIA_EVENT, REMOTE_MEDIA_EVENT_SDPOFFER_PROCESS);
                    object.addProperty(REMOTE_MEDIA_PARAMS_SDPANSWER, sdpAnswer);
                    result = object.toString();
                }
                break;
            default:
                break;
        }
        return result;
    }
}
