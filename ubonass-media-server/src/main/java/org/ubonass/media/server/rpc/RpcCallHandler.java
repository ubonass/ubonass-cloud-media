package org.ubonass.media.server.rpc;

import com.google.gson.JsonObject;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.server.core.MediaOptions;
import org.ubonass.media.server.core.Participant;
import org.ubonass.media.server.utils.RandomStringGenerator;


public class RpcCallHandler extends RpcHandler {

    private static final Logger logger = LoggerFactory.getLogger(RpcCallHandler.class);

    @Override
    public void handleRequest(Transaction transaction,
                              Request<JsonObject> request) throws Exception {
        super.handleRequest(transaction, request);
        RpcConnection rpcConnection =
                notificationService.getRpcConnection(
                        getParticipantPrivateIdByTransaction(transaction));
        if (rpcConnection == null) return;

        switch (request.getMethod()) {
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

    private void call(RpcConnection rpcConnection, Request<JsonObject> request) {
        String targetId = getStringParam(request, ProtocolElements.CALL_CALLEE_PARAM);
        //String clientId = getStringParam(request, ProtocolElements.CALL_FROMUSER_PARAM);
        JsonObject result = new JsonObject();
        /**
         * 如果callee不存在
         */
        if (!clusterRpcService.connectionExist(targetId)) {
            result.addProperty("method", ProtocolElements.CALL_METHOD);
            result.addProperty(ProtocolElements.CALL_RESPONSE_PARAM,
                    "rejected: user '" + targetId + "' is not registered");
            logger.info("rejected send incoming cluster to {} user,reason its not registered", targetId);
            notificationService.sendResponse(
                    rpcConnection.getParticipantPrivateId(), request.getId(), result);
            return;
        }

        /**
         * 手动创建sessionId,同时为sessionId进行配置
         */
        String sessionId = RandomStringGenerator.generateRandomChain();

        rpcConnection.setSessionId(sessionId);

        //添加进集群
        Participant participant =
                sessionManager.newCallParticipant(
                        rpcConnection.getMemberId(), sessionId, rpcConnection.getParticipantPrivateId(), rpcConnection.getParticipantPublicId());
        /**
         * 获取媒体参数
         */
        MediaOptions options = sessionManager.generateMediaOptions(request);
        /**
         * 已经在sessionId中发布了视频
         */
        sessionManager.call(participant, targetId, options, request.getId());

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
                !request.getParams().has(ProtocolElements.ONCALL_CALLER_PARAM)) return;
        String callerId = getStringParam(request, ProtocolElements.ONCALL_CALLER_PARAM);
        String sessionId = getStringParam(request, ProtocolElements.ONCALL_SESSION_PARAM);

        rpcConnection.setSessionId(sessionId);

        Participant participant =
                sessionManager.newCallParticipant(
                        rpcConnection.getMemberId(), sessionId, rpcConnection.getParticipantPrivateId(), rpcConnection.getParticipantPublicId());
        /**
         * 获取媒体参数
         */
        MediaOptions options = sessionManager.generateMediaOptions(request);
        sessionManager.onCallAccept(participant, callerId, options, request.getId());

        JsonObject accetpObject = new JsonObject();
        /*告知calleer对方已经接听*/
        accetpObject.addProperty(ProtocolElements.ONCALL_CALLER_PARAM, callerId);
        accetpObject.addProperty(ProtocolElements.ONCALL_CALLEE_PARAM, rpcConnection.getParticipantPublicId());
        accetpObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_ACCEPT);
        notificationService.sendNotificationByPublicId(
                callerId, ProtocolElements.ONCALL_METHOD, accetpObject);
    }

    private void onCallRejectProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM)
                .equals(ProtocolElements.ONCALL_EVENT_REJECT)) return;
        String callerId = getStringParam(request, ProtocolElements.ONCALL_CALLER_PARAM);
        String sessionId = getStringParam(request, ProtocolElements.ONCALL_SESSION_PARAM);

        sessionManager.onCallReject(sessionId, callerId, request.getId());

        JsonObject rejectObject = new JsonObject();
        if (request.getParams().has(ProtocolElements.ONCALL_EVENT_REJECT_REASON)) {
            rejectObject.addProperty(ProtocolElements.ONCALL_EVENT_REJECT_REASON, getStringParam(request,
                    ProtocolElements.ONCALL_EVENT_REJECT_REASON));
        }
        rejectObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_REJECT);

        notificationService.sendNotificationByPublicId(
                callerId, ProtocolElements.ONCALL_METHOD, rejectObject);
    }

    private void onCallHangupProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM)
                .equals(ProtocolElements.ONCALL_EVENT_HANGUP)) return;

        Participant participant;
        try {
            participant = sanityCheckOfSession(rpcConnection, "onCallHangup");
        } catch (CloudMediaException e) {
            return;
        }
        sessionManager.onCallHangup(participant, request.getId());
        logger.info("Participant {} has left session {}", participant.getParticipantPublicId(),
                rpcConnection.getSessionId());
    }

    protected void onIceCandidate(RpcConnection rpcConnection,
                                  Request<JsonObject> request) {
        Participant participant;
        try {
            participant = sanityCheckOfSession(rpcConnection, "onIceCandidate");
        } catch (CloudMediaException e) {
            return;
        }

        String endpointName = getStringParam(request, ProtocolElements.ONICECANDIDATE_EPNAME_PARAM);
        String candidate = getStringParam(request, ProtocolElements.ONICECANDIDATE_CANDIDATE_PARAM);
        String sdpMid = getStringParam(request, ProtocolElements.ONICECANDIDATE_SDPMIDPARAM);
        int sdpMLineIndex = getIntParam(request, ProtocolElements.ONICECANDIDATE_SDPMLINEINDEX_PARAM);

        sessionManager.onIceCandidate(participant, endpointName, candidate, sdpMLineIndex, sdpMid, request.getId());

    }

}
