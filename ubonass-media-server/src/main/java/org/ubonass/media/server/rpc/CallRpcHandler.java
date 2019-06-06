package org.ubonass.media.server.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.IceCandidateFoundEvent;
import org.kurento.jsonrpc.JsonUtils;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.server.call.UserMediaSession;
import org.ubonass.media.server.call.UserRpcConnection;
import org.ubonass.media.server.call.UserRpcRegistry;
import org.ubonass.media.server.kurento.KurentoClientProvider;
import org.ubonass.media.server.utils.RandomStringGenerator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CallRpcHandler extends RpcHandler {

    private static final Logger logger = LoggerFactory.getLogger(CallRpcHandler.class);

    @Autowired
    private UserRpcRegistry registry;

    @Autowired
    private RpcNotificationService notificationService;

    @Autowired
    private KurentoClientProvider kcProvider;

    /**
     * 每次建立视频或者音频通信后有一个唯一的KurentoSession
     * Key为房间号,如果不是room则随机生成
     */
    private Map<String, UserMediaSession> userMediaSessions = new ConcurrentHashMap<>();

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
            /*case ProtocolElements.CALL_STOP_METHOD:
                stop(rpcConnection, request);
                break;*/
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
            UserRpcConnection user = new UserRpcConnection(rpcConnection, userId);
            if (userId.isEmpty()) {
                responseMsg = "rejected: empty user name";
                result.addProperty(ProtocolElements.REGISTER_TYPE_PARAM, ProtocolElements.REGISTER_TYPE_REJECTED);
                result.addProperty(ProtocolElements.REGISTER_MESSAGE_PARAM, responseMsg);
            } else if (registry.exists(userId)) {
                responseMsg = "rejected: user '" + userId + "' already registered";
                result.addProperty(ProtocolElements.REGISTER_TYPE_PARAM, ProtocolElements.REGISTER_TYPE_REJECTED);
                result.addProperty(ProtocolElements.REGISTER_MESSAGE_PARAM, responseMsg);
            } else {
                logger.info("register use........");
                registry.register(user);
                result.addProperty(ProtocolElements.REGISTER_TYPE_PARAM, ProtocolElements.REGISTER_TYPE_ACCEPTD);
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
        UserRpcConnection caller = registry.getByUserRpcConnection(rpcConnection);
        JsonObject result = new JsonObject();
        if (registry.exists(targetId)) {

            logger.info("exists target user {}", targetId);

            //caller.setSdpOffer(getStringParam(request, ProtocolElements.CALL_SDPOFFER_PARAM));
            caller.setCallingTo(targetId);
            //生成session
            String sessionId = RandomStringGenerator.generateRandomChain();
            UserMediaSession userMediaSession =
                    new UserMediaSession(kcProvider.getKurentoClient());
            userMediaSessions.putIfAbsent(sessionId, userMediaSession);
            caller.setSessionId(sessionId);//保存sessionId

            String sdpOffer = getStringParam(request, ProtocolElements.CALL_SDPOFFER_PARAM);
            String sdpAnswer = userMediaSession.generateSdpAnswerForCaller(sdpOffer);

            UserRpcConnection callee = registry.getByUserId(targetId);
            callee.setCallingFrom(fromId);
            callee.setSessionId(sessionId);
            JsonObject notify = new JsonObject();

            logger.info("start send incoming cal to  target user {}", targetId);

            notify.addProperty(ProtocolElements.INCOMINGCALL_FROMUSER_PARAM, fromId);
            if (media != null)//如果未空表示全部
                notify.addProperty(ProtocolElements.INCOMINGCALL_MEDIA_PARAM, media);
            notificationService.sendNotification(
                    callee.getParticipantPrivateId(), ProtocolElements.INCOMINGCALL_METHOD, notify);

            logger.info("end send incoming cal to  target user {}", targetId);

            result.addProperty("method", ProtocolElements.CALL_METHOD);
            result.addProperty(ProtocolElements.CALL_RESPONSE_PARAM, "OK");
            result.addProperty(ProtocolElements.CALL_SDPANSWER_PARAM, sdpAnswer);

            notificationService.sendResponse(
                    rpcConnection.getParticipantPrivateId(), request.getId(), result);

        } else {
            result.addProperty("method", ProtocolElements.CALL_METHOD);
            result.addProperty(ProtocolElements.CALL_RESPONSE_PARAM,
                    "rejected: user '" + targetId + "' is not registered");
            logger.info("rejected send incoming call to {} user,reason its not registered", targetId);
            notificationService.sendResponse(
                    caller.getParticipantPrivateId(), request.getId(), result);
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
                .equals(ProtocolElements.ONCALL_EVENT_ACCEPT)) return;
        String fromId = getStringParam(request, ProtocolElements.ONCALL_FROMUSER_PARAM);
        String media = null;//如果为null则说明,all,视频语音一体
        if (request.getParams().has(ProtocolElements.ONCALL_MEDIA_PARAM))
            media = getStringParam(request, ProtocolElements.ONCALL_MEDIA_PARAM);

        final UserRpcConnection calleer = registry.getByUserId(fromId);
        final UserRpcConnection callee = registry.getByUserRpcConnection(rpcConnection);

        String targetId = calleer.getCallingTo();//这是当前发送者的ID

        logger.info("caller ParticipantPrivateId {},callee ParticipantPrivateId {}",
                calleer.getParticipantPrivateId(), callee.getParticipantPrivateId());
        logger.info("Accepted call from '{}' to '{}'", fromId, targetId);

        UserMediaSession pipeline = null;
        logger.info("caller session {},callee session {}",
                calleer.getSessionId(), callee.getSessionId());

        pipeline = userMediaSessions.get(calleer.getSessionId());

        callee.setWebRtcEndpoint(pipeline.getCalleeWebRtcEp());

        pipeline.getCalleeWebRtcEp().addIceCandidateFoundListener(
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

        calleer.setWebRtcEndpoint(pipeline.getCallerWebRtcEp());

        pipeline.getCallerWebRtcEp().addIceCandidateFoundListener(
                new EventListener<IceCandidateFoundEvent>() {

                    @Override
                    public void onEvent(IceCandidateFoundEvent event) {
                        JsonObject jsonObject = new JsonObject();
                        //jsonObject.addProperty("id", "iceCandidate");
                        jsonObject.add("candidate",
                                JsonUtils.toJsonObject(event.getCandidate()));
                        notificationService.sendNotification(
                                calleer.getParticipantPrivateId(),
                                ProtocolElements.ICECANDIDATE_METHOD,
                                jsonObject);
                    }
                });

        String calleeSdpOffer = getStringParam(request,
                ProtocolElements.ONCALL_SDPOFFER_PARAM);
        String calleeSdpAnswer = pipeline.generateSdpAnswerForCallee(calleeSdpOffer);
        JsonObject connectedObject = new JsonObject();
        //startCommunication.addProperty("id", "startCommunication");
        connectedObject.addProperty(
                ProtocolElements.ONCALL_SDPANSWER_PARAM, calleeSdpAnswer);
        connectedObject.addProperty(
                ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_CONNECTED);

        synchronized (callee) {
            notificationService.sendNotification(
                    rpcConnection.getParticipantPrivateId(), ProtocolElements.ONCALL_METHOD, connectedObject);
        }

        pipeline.getCalleeWebRtcEp().gatherCandidates();

        /*String callerSdpOffer = registry.getByUserId(fromId).getSdpOffer();
        String callerSdpAnswer = pipeline.generateSdpAnswerForCaller(callerSdpOffer);*/

        /*告知calleer对方已经接听*/
        JsonObject accetpObject = new JsonObject();
        accetpObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_ACCEPT);
        if (media != null)
            accetpObject.addProperty(ProtocolElements.ONCALL_MEDIA_PARAM, media);
        //accetpObject.addProperty(ProtocolElements.ONCALL_SDPANSWER_PARAM, callerSdpAnswer);
        synchronized (calleer) {
            notificationService.sendNotification(
                    calleer.getParticipantPrivateId(), ProtocolElements.ONCALL_METHOD, accetpObject);

        }
        pipeline.getCallerWebRtcEp().gatherCandidates();
    }

    private void onCallRejectProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_REJECT)
                .equals(ProtocolElements.ONCALL_EVENT_REJECT)) return;
        String fromId = getStringParam(request, ProtocolElements.ONCALL_FROMUSER_PARAM);
        final UserRpcConnection calleer = registry.getByUserId(fromId);
        JsonObject notify = new JsonObject();
        if (request.getParams().has(ProtocolElements.ONCALL_EVENT_REJECT_REASON)) {
            notify.addProperty(ProtocolElements.ONCALL_EVENT_REJECT_REASON, getStringParam(request,
                    ProtocolElements.ONCALL_EVENT_REJECT_REASON));
        }
        notify.addProperty(ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_REJECT);
        notificationService.sendNotification(
                calleer.getParticipantPrivateId(), ProtocolElements.ONCALL_METHOD, notify);
    }

    private void onCallHangupProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_HANGUP)
                .equals(ProtocolElements.ONCALL_EVENT_HANGUP)) return;

    }

    private void onIceCandidate(RpcConnection rpcConnection, Request<JsonObject> request) {

        //String endpointName = getStringParam(request, ProtocolElements.ONICECANDIDATE_EPNAME_PARAM);
        String candidate = getStringParam(request, ProtocolElements.ONICECANDIDATE_CANDIDATE_PARAM);
        String sdpMid = getStringParam(request, ProtocolElements.ONICECANDIDATE_SDPMIDPARAM);
        int sdpMLineIndex = getIntParam(request, ProtocolElements.ONICECANDIDATE_SDPMLINEINDEX_PARAM);

        UserRpcConnection user = registry.getByUserRpcConnection(rpcConnection);
        if (user != null) {
            IceCandidate cand = new IceCandidate(candidate, sdpMid, sdpMLineIndex);
            user.addCandidate(cand);
        }
    }

    public void stop(RpcConnection rpcConnection, Request<JsonObject> request) {
        UserRpcConnection stopperUser =
                registry.getByUserRpcConnection(rpcConnection);
        if (userMediaSessions.containsKey(stopperUser.getSessionId())) {
            UserMediaSession userMediaSession =
                    userMediaSessions.remove(stopperUser.getSessionId());
            userMediaSession.release();

            UserRpcConnection stoppedUser =
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
        }
    }

}
