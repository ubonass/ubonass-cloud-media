package org.ubonass.media.server.rpc;

import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.kurento.jsonrpc.JsonUtils;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.server.cluster.ClusterConnection;
import org.ubonass.media.server.core.MediaOptions;
import org.ubonass.media.server.core.Participant;
import org.ubonass.media.server.kurento.core.KurentoCallMediaStream;
import org.ubonass.media.server.kurento.core.KurentoCallMediaHandler;
import org.ubonass.media.server.utils.RandomStringGenerator;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class RpcCallHandler extends RpcHandler {

    private static final Logger logger = LoggerFactory.getLogger(RpcCallHandler.class);

    @Override
    public void handleRequest(Transaction transaction,
                              Request<JsonObject> request) throws Exception {
        super.handleRequest(transaction, request);
        String participantPrivateId =
                getParticipantPrivateIdByTransaction(transaction);
        RpcConnection rpcConnection =
                notificationService.getRpcConnection(participantPrivateId);
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
        String targetId = getStringParam(request, ProtocolElements.CALL_TARGETUSER_PARAM);
        String clientId = getStringParam(request, ProtocolElements.CALL_FROMUSER_PARAM);
        JsonObject result = new JsonObject();
        /**
         * 如果callee不存在
         */
        if (!notificationService.connectionExist(targetId)) {
            result.addProperty("method", ProtocolElements.CALL_METHOD);
            result.addProperty(ProtocolElements.CALL_RESPONSE_PARAM,
                    "rejected: user '" + targetId + "' is not registered");
            logger.info("rejected send incoming cluster to {} user,reason its not registered", targetId);
            notificationService.sendResponse(
                    rpcConnection.getParticipantPrivateId(), request.getId(), result);
            return;
        }

        logger.info("exists target user {} in {}",
                targetId, notificationService.connectionIsLocalMember(targetId) ? "local member" : "remote member");

        /**
         * 手动创建sessionId,同时为sessionId进行配置
         */
        String sessionId = RandomStringGenerator.generateRandomChain();

        rpcConnection.setSessionId(sessionId);

        Participant participant =
                sessionManager.newCallParticipant(
                        sessionId, rpcConnection.getParticipantPrivateId(), clientId);
        /**
         * 获取媒体参数
         */
        MediaOptions options = sessionManager.generateMediaOptions(request);
        /**
         * 已经在sessionId中发布了视频
         */
        sessionManager.call(participant, options, request.getId());

        JsonObject notifyInCallObject = new JsonObject();
        notifyInCallObject.addProperty(ProtocolElements.INCOMINGCALL_FROMUSER_PARAM, clientId);
        notifyInCallObject.addProperty(ProtocolElements.INCOMINGCALL_SESSION_PARAM, sessionId);
        notificationService.sendNotificationByPublicId(
                targetId, ProtocolElements.INCOMINGCALL_METHOD, notifyInCallObject);


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
        String sessionId = getStringParam(request, ProtocolElements.ONCALL_SESSION_PARAM);
        //caller不存在
        if (!notificationService.connectionExist(fromId)) return;

        rpcConnection.setSessionId(sessionId);
        Participant participant =
                sessionManager.newCallParticipant(
                        sessionId, rpcConnection.getParticipantPrivateId(),
                        rpcConnection.getParticipantPublicId());
        /**
         * 获取媒体参数
         */
        MediaOptions options = sessionManager.generateMediaOptions(request);
        sessionManager.onCallAccept(participant, options, request.getId());

        JsonObject accetpObject = new JsonObject();
        notificationService.sendNotificationByPublicId(
                fromId, ProtocolElements.ONCALL_METHOD, accetpObject);

    }

    private void onCallRejectProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM)
                .equals(ProtocolElements.ONCALL_EVENT_REJECT)) return;
        String fromId = getStringParam(request, ProtocolElements.ONCALL_FROMUSER_PARAM);

        if (!notificationService.connectionExist(fromId)) return;

        if (notificationService.connectionIsLocalMember(fromId)) {
            KurentoCallMediaStream session =
                    sessionManager.removeCallMediaStream(fromId);
            if (session != null)
                session.release();
        } else {
            //远程要移除掉
            ClusterConnection callerCluserConnection =
                    notificationService.getClusterConnection(fromId);

            Runnable runnable = new KurentoCallMediaHandler(
                    fromId, KurentoCallMediaHandler.MEDIA_EVENT_RELEASE_STREAM);
            clusterRpcService.executeToMember(runnable, callerCluserConnection.getMemberId());
        }

        JsonObject rejectObject = new JsonObject();
        if (request.getParams().has(ProtocolElements.ONCALL_EVENT_REJECT_REASON)) {
            rejectObject.addProperty(ProtocolElements.ONCALL_EVENT_REJECT_REASON, getStringParam(request,
                    ProtocolElements.ONCALL_EVENT_REJECT_REASON));
        }
        rejectObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_REJECT);

        notificationService.sendNotificationByPublicId(
                fromId, ProtocolElements.ONCALL_METHOD, rejectObject);
    }

    private void onCallHangupProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM)
                .equals(ProtocolElements.ONCALL_EVENT_HANGUP)) return;

        JsonObject hangupObject = new JsonObject();
        hangupObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM,
                ProtocolElements.ONCALL_EVENT_HANGUP);
        String targetId = null;
        KurentoCallMediaStream kurentoCallStream =
                sessionManager.getCallMediaStream(rpcConnection.getParticipantPublicId());
        /**
         * 来自caller挂断
         */
        if (rpcConnection.getParticipantPublicId()
                .equals(kurentoCallStream.getCallingFrom())) {
            targetId = kurentoCallStream.getCallingTo();
        } else {//来自callee挂断
            targetId = kurentoCallStream.getCallingFrom();
        }

        if (notificationService.connectionExist(targetId))
            notificationService.sendNotificationByPublicId(
                    targetId,
                    ProtocolElements.ONCALL_METHOD, hangupObject);

        KurentoCallMediaStream mediaStream =
                sessionManager.removeCallMediaStream(rpcConnection.getParticipantPublicId());
        if (mediaStream != null) {
            mediaStream.release();
            mediaStream = null;
        }

        if (notificationService
                .connectionIsLocalMember(targetId)) {
            mediaStream =
                    sessionManager.removeCallMediaStream(targetId);
            if (mediaStream != null) {
                mediaStream.release();
                mediaStream = null;
            }
        } else {
            ClusterConnection callerCluserConnection =
                    notificationService.getClusterConnection(targetId);
            Runnable runnable = new KurentoCallMediaHandler(
                    targetId, KurentoCallMediaHandler.MEDIA_EVENT_RELEASE_STREAM);
            clusterRpcService.executeToMember(runnable, callerCluserConnection.getMemberId());
        }
    }

    private void onIceCandidate(RpcConnection rpcConnection,
                                Request<JsonObject> request) {
        //endpointName这里是sessionId
        //String endpointName = getStringParam(request, ProtocolElements.ONICECANDIDATE_EPNAME_PARAM);
        /*String candidate = getStringParam(request, ProtocolElements.ONICECANDIDATE_CANDIDATE_PARAM);
        String sdpMid = getStringParam(request, ProtocolElements.ONICECANDIDATE_SDPMIDPARAM);
        int sdpMLineIndex = getIntParam(request, ProtocolElements.ONICECANDIDATE_SDPMLINEINDEX_PARAM);
        KurentoCallMediaStream kurentoCallSession =
                sessionManager.getCallMediaStream(rpcConnection.getParticipantPublicId());
        WebRtcEndpoint webRtcEndpoint =
                kurentoCallSession.getWebRtcEndpointById(rpcConnection.getParticipantPublicId());
        webRtcEndpoint.addIceCandidate(new IceCandidate(candidate, sdpMid, sdpMLineIndex));*/

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
