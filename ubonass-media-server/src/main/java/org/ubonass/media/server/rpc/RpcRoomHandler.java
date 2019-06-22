package org.ubonass.media.server.rpc;

import com.google.gson.JsonObject;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.java.client.CloudMediaRole;
import org.ubonass.media.server.core.Participant;
import org.ubonass.media.server.core.Token;
import org.ubonass.media.server.coturn.TurnCredentials;
import org.ubonass.media.server.kurento.core.KurentoTokenOptions;
import org.ubonass.media.server.utils.GeoLocation;
import org.ubonass.media.server.utils.RandomStringGenerator;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.InetAddress;

public class RpcRoomHandler extends RpcCallHandler {

    private static final Logger logger = LoggerFactory.getLogger(RpcRoomHandler.class);

    @Override
    public void handleRequest(Transaction transaction, Request<JsonObject> request) throws Exception {
        super.handleRequest(transaction, request);
        RpcConnection rpcConnection =
                notificationService.getRpcConnection(
                        getParticipantPrivateIdByTransaction(transaction));
        if (rpcConnection == null) return;
        switch (request.getMethod()) {

            /*case ProtocolElements.JOINROOM_METHOD:
                joinRoom(rpcConnection, request);
                break;*/

            case ProtocolElements.RECEIVEVIDEO_METHOD:
                receiveVideoFrom(rpcConnection, request);
                break;

            default:
                //logger.error("Unrecognized request {}", request);
                break;
        }
    }

    private void receiveVideoFrom(RpcConnection rpcConnection, Request<JsonObject> request) {
        //测试使用
        String sessionId = getStringParam(request, ProtocolElements.ONCALL_SESSION_PARAM);
        rpcConnection.setSessionId(sessionId);
        Token tokenObj = new Token(null, CloudMediaRole.SUBSCRIBER, "helloworld", null, null);
        Participant p = new Participant(
                rpcConnection.getMemberId(), rpcConnection.getParticipantPrivateId(), rpcConnection.getParticipantPublicId(),
                sessionId, tokenObj, null, null, null, null);
        sessionManager.joinRoom(p,sessionId,request.getId());

        Participant participant;
        try {
            participant = sanityCheckOfSession(rpcConnection, "subscribe");
        } catch (CloudMediaException e) {
            return;
        }
        String senderName = getStringParam(request, ProtocolElements.RECEIVEVIDEO_SENDER_PARAM);
        if (senderName.contains("_"))
            senderName = senderName.substring(0, senderName.indexOf("_"));
        String sdpOffer = getStringParam(request, ProtocolElements.RECEIVEVIDEO_SDPOFFER_PARAM);

        sessionManager.subscribe(participant, senderName, sdpOffer, request.getId());
    }
}
