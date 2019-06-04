package org.ubonass.media.server.call;

import com.google.gson.JsonObject;
import org.kurento.client.IceCandidate;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.jsonrpc.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.server.rpc.RpcConnection;
import org.ubonass.media.server.rpc.RpcNotificationService;

import java.util.ArrayList;
import java.util.List;

public class UserRpcConnection extends RpcConnection {

    @Autowired
    private RpcNotificationService notificationService;

    private static final Logger logger =
            LoggerFactory.getLogger(UserRpcConnection.class);

    private final String userId;
    private String sdpOffer;
    private String callingTo;
    private String callingFrom;
    private WebRtcEndpoint webRtcEndpoint;
    private final List<IceCandidate> candidateList = new ArrayList<IceCandidate>();

    public UserRpcConnection(RpcConnection rpcConnection, String userId) {
        super(rpcConnection.getSession());
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    public String getSdpOffer() {
        return sdpOffer;
    }

    public void setSdpOffer(String sdpOffer) {
        this.sdpOffer = sdpOffer;
    }

    public String getCallingTo() {
        return callingTo;
    }

    public void setCallingTo(String callingTo) {
        this.callingTo = callingTo;
    }

    public String getCallingFrom() {
        return callingFrom;
    }

    public void setCallingFrom(String callingFrom) {
        this.callingFrom = callingFrom;
    }

    public void sendResponse(Integer transactionId, JsonObject result) {
        logger.info("Sending response from user '{}': {}", userId,
                result.toString());
        notificationService.sendResponse(getParticipantPrivateId(), transactionId, result);
    }

    public void sendNotification(final String method,
                                 final JsonObject params) {
        logger.info("Sending notification from user '{}',{}: {}",
                userId, method, params.toString());
        notificationService.sendNotification(getParticipantPrivateId(), method, params);
    }

    public void sendErrorResponse(Integer transactionId,
                                  JsonObject data, CloudMediaException error) {
        logger.debug("Sending Error Response from user '{}': {}", userId, data.toString());

        notificationService.sendErrorResponse(getParticipantPrivateId(),
                transactionId, data, error);
    }

    public void setWebRtcEndpoint(WebRtcEndpoint webRtcEndpoint) {
        this.webRtcEndpoint = webRtcEndpoint;

        for (IceCandidate e : candidateList) {
            this.webRtcEndpoint.addIceCandidate(e);
        }
        this.candidateList.clear();
    }

    public void addCandidate(IceCandidate candidate) {
        if (this.webRtcEndpoint != null) {
            this.webRtcEndpoint.addIceCandidate(candidate);
        } else {
            candidateList.add(candidate);
        }
    }

    public void clear() {
        this.webRtcEndpoint = null;
        this.candidateList.clear();
    }
}
