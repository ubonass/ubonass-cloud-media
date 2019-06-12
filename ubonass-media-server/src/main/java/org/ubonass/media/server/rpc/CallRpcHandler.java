package org.ubonass.media.server.rpc;

import com.google.gson.JsonObject;
import org.kurento.client.*;
import org.kurento.jsonrpc.JsonUtils;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.server.cluster.ClusterConnection;
import org.ubonass.media.server.kurento.core.KurentoCallMediaStream;
import org.ubonass.media.server.kurento.core.KurentoCallTask;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class CallRpcHandler extends RpcHandler {

    private static final Logger logger = LoggerFactory.getLogger(CallRpcHandler.class);

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
        String media = null;
        if (request.getParams().has(ProtocolElements.CALL_MEDIA_PARAM))
            media = getStringParam(request, ProtocolElements.CALL_MEDIA_PARAM);
        JsonObject result = new JsonObject();
        //判断targetId在当前主机上是否存在
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

        KurentoCallMediaStream callerStream =
                new KurentoCallMediaStream(
                        kcProvider.getKurentoClient(), clientId, targetId);

        WebRtcEndpoint webRtcEndpoint =
                callerStream.createWebRtcEndpoint(clientId);

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
        notifyInCallObject.addProperty(ProtocolElements.INCOMINGCALL_FROMUSER_PARAM, clientId);
        //notifyInCallObject.addProperty(ProtocolElements.INCOMINGCALL_SESSION_PARAM, sessionId);
        if (media != null)//如果未空表示全部
            notifyInCallObject.addProperty(ProtocolElements.INCOMINGCALL_MEDIA_PARAM, media);

        if (!notificationService.connectionIsLocalMember(targetId)) {
            //创建rtpEndpoint
            RtpEndpoint rtpEndPoint = callerStream.createRtpEndPoint(clientId);

            rtpEndPoint.addErrorListener(new EventListener<ErrorEvent>() {

                @Override
                public void onEvent(ErrorEvent event) {
                    logger.warn("---error en RTP {}", event.getDescription());

                }
            });
            rtpEndPoint.addConnectionStateChangedListener(
                    new EventListener<ConnectionStateChangedEvent>() {

                        @Override
                        public void onEvent(ConnectionStateChangedEvent event) {
                            logger.warn("---nuevo estado del RTP {}", event.getNewState());
                        }
                    });
            webRtcEndpoint.connect(rtpEndPoint);
        }

        sessionManager.addCallSession(clientId, callerStream);

        notificationService.sendNotificationByClientId(
                targetId, ProtocolElements.INCOMINGCALL_METHOD, notifyInCallObject);

        result.addProperty("method", ProtocolElements.CALL_METHOD);
        result.addProperty(ProtocolElements.CALL_RESPONSE_PARAM, "OK");
        result.addProperty(ProtocolElements.CALL_SDPANSWER_PARAM, sdpAnswer);

        notificationService.sendResponse(
                rpcConnection.getParticipantPrivateId(), request.getId(), result);

        webRtcEndpoint.gatherCandidates();

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

        if (!notificationService.connectionExist(fromId)) return;

        String media = null;//如果为null则说明,all,视频语音一体
        if (request.getParams().has(ProtocolElements.ONCALL_MEDIA_PARAM))
            media = getStringParam(request, ProtocolElements.ONCALL_MEDIA_PARAM);
        /**
         * 如果当前连接所在的host id和 caller的 host id 一致说明在一台服务器上,
         * 否则这里需要重新创建MediaPipleline
         */
        KurentoCallMediaStream calleeStream = null;
        /**
         * 判断callerConnection是否处于本host,如果不在本机上则需要自主创建一个kurentoCallSession
         */
        if (notificationService.connectionIsLocalMember(fromId)) {
            calleeStream =
                    sessionManager.getCallSession(fromId);
            logger.info("create calleeStream success ....");

        } else {
            //需要重新创建KurentoCallSession
            calleeStream = new KurentoCallMediaStream(
                    kcProvider.getKurentoClient(),
                    fromId,
                    rpcConnection.getClientId());
            logger.info("create calleeStream success ..........");
        }

        WebRtcEndpoint calleewebRtcEndpoint =
                calleeStream.createWebRtcEndpoint(
                        rpcConnection.getClientId());

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

        if (notificationService.connectionIsLocalMember(fromId)) {
            WebRtcEndpoint callerwebRtcEndpoint =
                    calleeStream.getWebRtcEndpointById(fromId);
            callerwebRtcEndpoint.connect(calleewebRtcEndpoint);
            calleewebRtcEndpoint.connect(callerwebRtcEndpoint);
        } else { //表示远程主机
            //要创建rtpEndpoint
            RtpEndpoint rtpEndpoint =
                    calleeStream.createRtpEndPoint(rpcConnection.getClientId());
            rtpEndpoint.addErrorListener(new EventListener<ErrorEvent>() {

                @Override
                public void onEvent(ErrorEvent event) {
                    logger.warn("error en RTP {}", event.getDescription());

                }
            });
            rtpEndpoint.addConnectionStateChangedListener(new EventListener<ConnectionStateChangedEvent>() {

                @Override
                public void onEvent(ConnectionStateChangedEvent event) {
                    logger.warn("nuevo estado del RTP {}", event.getNewState());
                }
            });
            String rtpOffer = rtpEndpoint.generateOffer();
            /**
             * 将sdpOffer发送到Caller的RTP进行处理,同时接受sdpAnswer
             * 提交一个异步任务
             */
            Callable callable = new KurentoCallTask(
                    fromId, rtpOffer, "rtpProcessOffer");
            ClusterConnection callerCluserConnection =
                    notificationService.getClusterConnection(fromId);
            Future<String> processAnswer = (Future<String>)
                    clusterRpcService.submitTaskToMembers(callable,
                            callerCluserConnection.getMemberId());
            try {
                String rtpAnswer = processAnswer.get();
                logger.info("rtpAnswer {}", rtpAnswer);
                rtpEndpoint.processAnswer(rtpAnswer);
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
            //将webrtcendpoint 作为rtpEndpoint的消费者
            rtpEndpoint.connect(calleewebRtcEndpoint);
        }
        sessionManager.addCallSession(rpcConnection.getClientId(), calleeStream);
        /**
         * 回复给客户端
         */
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

        JsonObject accetpObject = new JsonObject();
        /*告知calleer对方已经接听*/
        accetpObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_ACCEPT);
        if (media != null)
            accetpObject.addProperty(ProtocolElements.ONCALL_MEDIA_PARAM, media);

        notificationService.sendNotificationByClientId(
                fromId,ProtocolElements.ONCALL_METHOD, accetpObject);

    }

    private void onCallRejectProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM)
                .equals(ProtocolElements.ONCALL_EVENT_REJECT)) return;
        String fromId = getStringParam(request, ProtocolElements.ONCALL_FROMUSER_PARAM);

        if (!notificationService.connectionExist(fromId)) return;

        if (notificationService.connectionIsLocalMember(fromId)) {
            KurentoCallMediaStream session =
                    sessionManager.removeCallSession(fromId);
            if (session != null)
                session.release();
        } else {
            //远程要移除掉
            ClusterConnection callerCluserConnection =
                    notificationService.getClusterConnection(fromId);
            Callable callable = new KurentoCallTask(
                    fromId, null, "releaseMediaStream");
            clusterRpcService.submitTaskToMembers(callable,
                    callerCluserConnection.getMemberId());
        }

        JsonObject rejectObject = new JsonObject();
        if (request.getParams().has(ProtocolElements.ONCALL_EVENT_REJECT_REASON)) {
            rejectObject.addProperty(ProtocolElements.ONCALL_EVENT_REJECT_REASON, getStringParam(request,
                    ProtocolElements.ONCALL_EVENT_REJECT_REASON));
        }
        rejectObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_REJECT);

        notificationService.sendNotificationByClientId(
                fromId, ProtocolElements.ONCALL_METHOD, rejectObject);
    }

    private void onCallHangupProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM)
                .equals(ProtocolElements.ONCALL_EVENT_HANGUP)) return;
        KurentoCallMediaStream kurentoCallStream =
                sessionManager.getCallSession(rpcConnection.getClientId());

        JsonObject hangupObject = new JsonObject();
        hangupObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM,
                ProtocolElements.ONCALL_EVENT_HANGUP);
        /**
         * 来自caller挂断
         */
        if (rpcConnection.getClientId()
                .equals(kurentoCallStream.getCallingFrom())) {
            notificationService.sendNotificationByClientId(
                    kurentoCallStream.getCallingTo(),
                    ProtocolElements.ONCALL_METHOD, hangupObject);
        } else {//来自callee挂断
            notificationService.sendNotificationByClientId(
                    kurentoCallStream.getCallingFrom(),
                    ProtocolElements.ONCALL_METHOD, hangupObject);
        }

        /*if (callSessions.containsKey(kurentoCallSession.getCallingFrom())) {
            KurentoCallMediaStream session =
                    callSessions.remove(kurentoCallSession.getCallingFrom());
            if (session != null)
                session.release();
        }

        if (callSessions.containsKey(kurentoCallSession.getCallingTo())) {
            KurentoCallMediaStream session =
                    callSessions.remove(kurentoCallSession.getCallingTo());
            if (session != null)
                session.release();
        }*/
    }

    private void onIceCandidate(RpcConnection rpcConnection,
                                Request<JsonObject> request) {
        //endpointName这里是sessionId
        //String endpointName = getStringParam(request, ProtocolElements.ONICECANDIDATE_EPNAME_PARAM);
        String candidate = getStringParam(request, ProtocolElements.ONICECANDIDATE_CANDIDATE_PARAM);
        String sdpMid = getStringParam(request, ProtocolElements.ONICECANDIDATE_SDPMIDPARAM);
        int sdpMLineIndex = getIntParam(request, ProtocolElements.ONICECANDIDATE_SDPMLINEINDEX_PARAM);
        KurentoCallMediaStream kurentoCallSession =
                sessionManager.getCallSession(rpcConnection.getClientId());
        WebRtcEndpoint webRtcEndpoint =
                kurentoCallSession.getWebRtcEndpointById(rpcConnection.getClientId());
        webRtcEndpoint.addIceCandidate(new IceCandidate(candidate, sdpMid, sdpMLineIndex));
    }

}
