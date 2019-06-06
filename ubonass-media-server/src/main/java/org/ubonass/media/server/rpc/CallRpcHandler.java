package org.ubonass.media.server.rpc;

import com.google.gson.JsonObject;
import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.IceCandidateFoundEvent;
import org.kurento.client.WebRtcEndpoint;
import org.kurento.jsonrpc.JsonUtils;
import org.kurento.jsonrpc.Session;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.server.call.KurentoCallSession;
import org.ubonass.media.server.kurento.KurentoClientProvider;
import org.ubonass.media.server.utils.RandomStringGenerator;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CallRpcHandler extends RpcHandler {

    private static final Logger logger = LoggerFactory.getLogger(CallRpcHandler.class);

    /*@Autowired
    private UserRpcRegistry registry;*/

    @Autowired
    private RpcNotificationService notificationService;

    @Autowired
    private KurentoClientProvider kcProvider;
    /**
     * Key为sessionId
     * value 为KurentoMediaSession
     */

    private Map<String, KurentoCallSession> callSessions = new ConcurrentHashMap<>();

    @Override
    public void handleRequest(Transaction transaction,
                              Request<JsonObject> request) throws Exception {
        super.handleRequest(transaction, request);
        String participantPrivateId =
                getParticipantPrivateIdByTransaction(transaction);
        logger.info("WebSocket session #{} - Request: {}", participantPrivateId, request);
        RpcConnection rpcConnection;
        if (ProtocolElements.REGISTER_METHOD.equals(request.getMethod())) {
            // Store new RpcConnection information if method 'keepLive'
            rpcConnection = notificationService.newRpcConnection(transaction, request);
        } else if (notificationService.getRpcConnection(participantPrivateId) == null) {
            // Throw exception if any method is called before 'joinCloud'
            logger.warn(
                    "No connection found for participant with privateId {} when trying to execute method '{}'. Method 'Session.connect()' must be the first operation called in any session",
                    participantPrivateId, request.getMethod());
            throw new CloudMediaException(Code.TRANSPORT_ERROR_CODE,
                    "No connection found for participant with privateId " + participantPrivateId
                            + ". Method 'Session.connect()' must be the first operation called in any session");
        }

        rpcConnection = notificationService.addTransaction(transaction, request);

        transaction.startAsync();

        switch (request.getMethod()) {
            case ProtocolElements.REGISTER_METHOD:
                register(rpcConnection, request);
                break;
            case ProtocolElements.CALL_METHOD:
                call(rpcConnection, request);
                break;
            case ProtocolElements.ONCALL_METHOD:
                onCall(rpcConnection, request);
                break;
            case ProtocolElements.ONICECANDIDATE_METHOD:
                onIceCandidate(rpcConnection, request);
                break;
            default:
                break;
        }
    }

    private void register(RpcConnection rpcConnection, Request<JsonObject> request) {
        JsonObject result = new JsonObject();
        String userId = getStringParam(request, ProtocolElements.REGISTER_USER_PARAM);
        result.addProperty("method", ProtocolElements.REGISTER_METHOD);
        result.addProperty(ProtocolElements.REGISTER_USER_PARAM, userId);
        String responseMsg = null;
        if (!request.getParams().has(ProtocolElements.REGISTER_USER_PARAM)) {
            responseMsg = "rejected: empty user key";
            //result.addProperty("method", ProtocolElements.REGISTER_METHOD);
            result.addProperty(ProtocolElements.REGISTER_TYPE_PARAM, ProtocolElements.REGISTER_TYPE_REJECTED);
            result.addProperty(ProtocolElements.REGISTER_MESSAGE_PARAM, responseMsg);
        } else {
            //UserSession user = new UserSession(rpcConnection, userId);
            if (userId.isEmpty()) {
                responseMsg = "rejected: empty user name";
                result.addProperty(ProtocolElements.REGISTER_TYPE_PARAM, ProtocolElements.REGISTER_TYPE_REJECTED);
                result.addProperty(ProtocolElements.REGISTER_MESSAGE_PARAM, responseMsg);
            } else {
                Session session = onlineClients.putIfAbsent(userId, rpcConnection.getSession());
                if (session != null) {
                    responseMsg = "rejected: user '" + userId + "' already registered";
                    result.addProperty(ProtocolElements.REGISTER_TYPE_PARAM, ProtocolElements.REGISTER_TYPE_REJECTED);
                    result.addProperty(ProtocolElements.REGISTER_MESSAGE_PARAM, responseMsg);
                } else {
                    result.addProperty(ProtocolElements.REGISTER_TYPE_PARAM, ProtocolElements.REGISTER_TYPE_ACCEPTD);
                }
            }
        }
        notificationService.sendResponse(rpcConnection.getParticipantPrivateId(), request.getId(), result);
    }

    private void call(RpcConnection rpcConnection, Request<JsonObject> request) {
        String targetId = getStringParam(request, ProtocolElements.CALL_TARGETUSER_PARAM);
        String fromId = getStringParam(request, ProtocolElements.CALL_FROMUSER_PARAM);
        String media = null;
        if (request.getParams().has(ProtocolElements.CALL_MEDIA_PARAM))
            media = getStringParam(request, ProtocolElements.CALL_MEDIA_PARAM);
        JsonObject result = new JsonObject();
        if (onlineClients.containsKey(targetId)) {
            logger.info("exists target user {}", targetId);
            //caller.setCallingTo(targetId);
            //生成session
            //String sessionId = RandomStringGenerator.generateRandomChain();
            KurentoCallSession callSession =
                    new KurentoCallSession(
                            kcProvider.getKurentoClient(),
                            rpcConnection.getSession().getSessionId(),
                            onlineClients.get(targetId).getSessionId());
            //rpcConnection.setSessionId(sessionId);

            callSessions.putIfAbsent(rpcConnection.getSession().getSessionId(), callSession);
            //callSessions.putIfAbsent(onlineClients.get(targetId).getSessionId(), callSession);

            WebRtcEndpoint webRtcEndpoint =
                    callSession.createWebRtcEndpoint(
                            rpcConnection.getParticipantPrivateId());
            /*WebRtcEndpoint calleewebRtcEndpoint =
                    callSession.createWebRtcEndpoint(
                            onlineClients.get(targetId).getSessionId());*/

            webRtcEndpoint.addIceCandidateFoundListener(
                    new EventListener<IceCandidateFoundEvent>() {
                        @Override
                        public void onEvent(IceCandidateFoundEvent event) {
                            JsonObject jsonObject = new JsonObject();
                            //jsonObject.addProperty("id", "iceCandidate");
                            jsonObject.add("candidate",
                                    JsonUtils.toJsonObject(event.getCandidate()));
                            notificationService.sendNotification(
                                    rpcConnection.getParticipantPrivateId(),
                                    ProtocolElements.ICECANDIDATE_METHOD,
                                    jsonObject);
                        }
                    });

            String sdpOffer = getStringParam(request, ProtocolElements.CALL_SDPOFFER_PARAM);
            String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);

            JsonObject notifyInCallObject = new JsonObject();
            notifyInCallObject.addProperty(ProtocolElements.INCOMINGCALL_FROMUSER_PARAM, fromId);
            //notifyInCallObject.addProperty(ProtocolElements.INCOMINGCALL_SESSION_PARAM, sessionId);
            if (media != null)//如果未空表示全部
                notifyInCallObject.addProperty(ProtocolElements.INCOMINGCALL_MEDIA_PARAM, media);
            try {
                onlineClients.get(targetId)
                        .sendNotification(ProtocolElements.INCOMINGCALL_METHOD, notifyInCallObject);
            } catch (IOException e) {
                e.printStackTrace();
            }

            result.addProperty("method", ProtocolElements.CALL_METHOD);
            result.addProperty(ProtocolElements.CALL_RESPONSE_PARAM, "OK");
            result.addProperty(ProtocolElements.CALL_SDPANSWER_PARAM, sdpAnswer);

            notificationService.sendResponse(
                    rpcConnection.getParticipantPrivateId(), request.getId(), result);

            webRtcEndpoint.gatherCandidates();

        } else {
            result.addProperty("method", ProtocolElements.CALL_METHOD);
            result.addProperty(ProtocolElements.CALL_RESPONSE_PARAM,
                    "rejected: user '" + targetId + "' is not registered");
            logger.info("rejected send incoming call to {} user,reason its not registered", targetId);
            notificationService.sendResponse(
                    rpcConnection.getParticipantPrivateId(), request.getId(), result);
        }
    }

    private void onCall(RpcConnection rpcConnection, Request<JsonObject> request) {
        String event = getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM);
        switch (event) {
            case ProtocolElements.ONCALL_EVENT_ACCEPT:
                onCallAcceptProcess(rpcConnection, request);
                break;
            case ProtocolElements.ONCALL_EVENT_REJECT:
                onCallRejectProcess(rpcConnection, request);
                break;
            case ProtocolElements.ONCALL_EVENT_HANGUP:
                onCallHangupProcess(rpcConnection, request);
                break;
        }
    }

    private void onCallAcceptProcess(RpcConnection rpcConnection, Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM)
                .equals(ProtocolElements.ONCALL_EVENT_ACCEPT) ||
                !request.getParams().has(ProtocolElements.ONCALL_FROMUSER_PARAM)) return;
        String fromId = getStringParam(request, ProtocolElements.ONCALL_FROMUSER_PARAM);
        String media = null;//如果为null则说明,all,视频语音一体
        if (request.getParams().has(ProtocolElements.ONCALL_MEDIA_PARAM))
            media = getStringParam(request, ProtocolElements.ONCALL_MEDIA_PARAM);

        KurentoCallSession kurentoCallSession =
                callSessions.get(onlineClients.get(fromId).getSessionId());
        callSessions.putIfAbsent(rpcConnection.getSession().getSessionId(),kurentoCallSession);

        WebRtcEndpoint calleewebRtcEndpoint =
                kurentoCallSession.createWebRtcEndpoint(
                        rpcConnection.getSession().getSessionId());

        WebRtcEndpoint callerwebRtcEndpoint =
                kurentoCallSession.getWebRtcEndpointBySessionId(
                        onlineClients.get(fromId).getSessionId());

        calleewebRtcEndpoint.addIceCandidateFoundListener(
                new EventListener<IceCandidateFoundEvent>() {

                    @Override
                    public void onEvent(IceCandidateFoundEvent event) {
                        JsonObject jsonObject = new JsonObject();
                        //jsonObject.addProperty("id", "iceCandidate");
                        jsonObject.add("candidate",
                                JsonUtils.toJsonObject(event.getCandidate()));
                        notificationService.sendNotification(
                                rpcConnection.getSession().getSessionId(),
                                ProtocolElements.ICECANDIDATE_METHOD,
                                jsonObject);
                    }
                });

        callerwebRtcEndpoint.connect(calleewebRtcEndpoint);
        calleewebRtcEndpoint.connect(callerwebRtcEndpoint);

        String sdpOffer = getStringParam(request, ProtocolElements.ONCALL_SDPOFFER_PARAM);
        String sdpAnswer = calleewebRtcEndpoint.processOffer(sdpOffer);

        JsonObject connectedObject = new JsonObject();
        //startCommunication.addProperty("id", "startCommunication");
        connectedObject.addProperty(
                ProtocolElements.ONCALL_SDPANSWER_PARAM, sdpAnswer);
        connectedObject.addProperty(
                ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_CONNECTED);
        notificationService.sendNotification(
                rpcConnection.getParticipantPrivateId(), ProtocolElements.ONCALL_METHOD, connectedObject);

        calleewebRtcEndpoint.gatherCandidates();

        /*告知calleer对方已经接听*/
        JsonObject accetpObject = new JsonObject();
        accetpObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_ACCEPT);
        if (media != null)
            accetpObject.addProperty(ProtocolElements.ONCALL_MEDIA_PARAM, media);
        //accetpObject.addProperty(ProtocolElements.ONCALL_SDPANSWER_PARAM, callerSdpAnswer);
        notificationService.sendNotification(
                onlineClients.get(fromId).getSessionId(), ProtocolElements.ONCALL_METHOD, accetpObject);
    }

    private void onCallRejectProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM)
                .equals(ProtocolElements.ONCALL_EVENT_REJECT)) return;
        String fromId = getStringParam(request, ProtocolElements.ONCALL_FROMUSER_PARAM);
        if (callSessions.containsKey(
                onlineClients.get(fromId).getSessionId())) {
            KurentoCallSession session = callSessions.remove(
                    onlineClients.get(fromId).getSessionId());
            if (session != null)
                session.release();
        }
        JsonObject rejectObject = new JsonObject();
        if (request.getParams().has(ProtocolElements.ONCALL_EVENT_REJECT_REASON)) {
            rejectObject.addProperty(ProtocolElements.ONCALL_EVENT_REJECT_REASON, getStringParam(request,
                    ProtocolElements.ONCALL_EVENT_REJECT_REASON));
        }
        rejectObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_REJECT);
        notificationService.sendNotification(
                onlineClients.get(fromId).getSessionId(), ProtocolElements.ONCALL_METHOD, rejectObject);
    }

    private void onCallHangupProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM)
                .equals(ProtocolElements.ONCALL_EVENT_HANGUP)) return;
        KurentoCallSession kurentoCallSession =
                callSessions.get(rpcConnection.getParticipantPrivateId());

        if (callSessions.containsKey(kurentoCallSession.getCallingFrom())) {
            KurentoCallSession session =
                    callSessions.remove(kurentoCallSession.getCallingFrom());
            if (session != null)
                session.release();
        }

        if (callSessions.containsKey(kurentoCallSession.getCallingTo())) {
            KurentoCallSession session =
                    callSessions.remove(kurentoCallSession.getCallingTo());
            if (session != null)
                session.release();
        }
        JsonObject hangupObject = new JsonObject();
        hangupObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM,
                ProtocolElements.ONCALL_EVENT_HANGUP);
        if (rpcConnection.getParticipantPrivateId().equals(kurentoCallSession.getCallingFrom())) {
            notificationService.sendNotification(
                    kurentoCallSession.getCallingTo(),
                    ProtocolElements.ONCALL_METHOD, hangupObject);
        } else {
            notificationService.sendNotification(
                    kurentoCallSession.getCallingFrom(),
                    ProtocolElements.ONCALL_METHOD, hangupObject);
        }
    }

    private void onIceCandidate(RpcConnection rpcConnection,
                                Request<JsonObject> request) {
        //endpointName这里是sessionId
        //String endpointName = getStringParam(request, ProtocolElements.ONICECANDIDATE_EPNAME_PARAM);
        String candidate = getStringParam(request, ProtocolElements.ONICECANDIDATE_CANDIDATE_PARAM);
        String sdpMid = getStringParam(request, ProtocolElements.ONICECANDIDATE_SDPMIDPARAM);
        int sdpMLineIndex = getIntParam(request, ProtocolElements.ONICECANDIDATE_SDPMLINEINDEX_PARAM);
        KurentoCallSession kurentoCallSession =
                callSessions.get(rpcConnection.getSession().getSessionId());
        WebRtcEndpoint webRtcEndpoint =
                kurentoCallSession.getWebRtcEndpointBySessionId(rpcConnection.getSession().getSessionId());
        webRtcEndpoint.addIceCandidate(new IceCandidate(candidate, sdpMid, sdpMLineIndex));
    }

    public void stop(RpcConnection rpcConnection, Request<JsonObject> request) {
        /*UserSession stopperUser =
                registry.getByUserRpcConnection(rpcConnection);
        if (callerMediaSessions.containsKey(stopperUser.getSessionId())) {
            KurentoCallSession userMediaSession =
                    callerMediaSessions.remove(stopperUser.getSessionId());
            userMediaSession.release();

            UserSession stoppedUser =
                    (stopperUser.getCallingFrom() != null) ? registry.getByUserId(stopperUser
                            .getCallingFrom()) : stopperUser.getCallingTo() != null ? registry
                            .getByUserId(stopperUser.getCallingTo()) : null;

            if (stoppedUser != null) {
                JsonObject message = new JsonObject();
                //message.addProperty("id", "stopCommunication");

                notificationService.sendNotification(
                        stoppedUser.getParticipantPrivateId(), ProtocolElements.STOP_COMMUNICATION_METHOD, null);
                stoppedUser.clear();
            }
            stopperUser.clear();
        }*/
    }

}
