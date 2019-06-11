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
import org.ubonass.media.server.kurento.core.KurentoCallSession;

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
        /*RpcConnection calleeRpcConnection = sessionManager.getRpcConnection(targetId);
        ClusterConnection calleeCluserConnection = sessionManager.getCluserConnection(targetId);*/
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

        ClusterConnection calleeClusterConnection =
                notificationService.getClusterConnection(targetId);
        RpcConnection calleeRpcConnection =
                notificationService.getRpcConnection(calleeClusterConnection.getSessionId());

        logger.info("exists target user {} in {}",
                targetId, calleeRpcConnection == null ? "cluster remote" : "local");

        KurentoCallSession callSession =
                new KurentoCallSession(
                        kcProvider.getKurentoClient(), clientId, targetId);

        WebRtcEndpoint webRtcEndpoint =
                callSession.createWebRtcEndpoint(clientId);

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
        /**
         * 如果是集群的话这个消息应该是由targetId所在的服务器发送
         * 如果目标连接也是连接在本台服务器则直接发送
         */
        //表示在本机上
        if (calleeRpcConnection != null) {

            notificationService.sendNotification(
                    calleeRpcConnection.getParticipantPrivateId(),
                    ProtocolElements.INCOMINGCALL_METHOD, notifyInCallObject);
        } else {
            //创建rtpEndpoint
            RtpEndpoint rtpEndPoint = callSession.createRtpEndPoint(
                    rpcConnection.getParticipantPrivateId());

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

            notificationService.sendMemberNotification(
                    calleeClusterConnection.getClientId(),ProtocolElements.INCOMINGCALL_METHOD, notifyInCallObject);
            /**
             * 将webrtcEnd
             */
            webRtcEndpoint.connect(rtpEndPoint);
        }

        sessionManager.addCallSession(clientId, callSession);

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

        if (!notificationService.connectionExist(fromId)) {
            //用户不存在
            return;
        }
        //判断caller是否还在线
        ClusterConnection callerCluserConnection =
                notificationService.getClusterConnection(fromId);

        RpcConnection callerRpcConnection =
                notificationService.getRpcConnection(callerCluserConnection.getSessionId());

        String media = null;//如果为null则说明,all,视频语音一体
        if (request.getParams().has(ProtocolElements.ONCALL_MEDIA_PARAM))
            media = getStringParam(request, ProtocolElements.ONCALL_MEDIA_PARAM);
        /**
         * 如果当前连接所在的host id和 caller的 host id 一致说明在一台服务器上,
         * 否则这里需要重新创建MediaPipleline
         */
        KurentoCallSession kurentoCallSession = null;
        /**
         * 判断callerConnection是否处于本host,如果不在本机上则需要自主创建一个kurentoCallSession
         */
        if (callerRpcConnection != null) {
            kurentoCallSession =
                    sessionManager.getCallSession(callerRpcConnection.getClientId());

        } else {
            //需要重新创建KurentoCallSession
            kurentoCallSession = new KurentoCallSession(
                    kcProvider.getKurentoClient(),
                    fromId,
                    rpcConnection.getClientId());
        }

        WebRtcEndpoint calleewebRtcEndpoint =
                kurentoCallSession.createWebRtcEndpoint(
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

        if (callerRpcConnection != null) {
            WebRtcEndpoint callerwebRtcEndpoint =
                    kurentoCallSession.getWebRtcEndpointById(
                            callerRpcConnection.getClientId());
            callerwebRtcEndpoint.connect(calleewebRtcEndpoint);
            calleewebRtcEndpoint.connect(callerwebRtcEndpoint);
        } else { //表示远程主机
            //要创建rtpEndpoint
            RtpEndpoint rtpEndpoint =
                    kurentoCallSession.createRtpEndPoint(rpcConnection.getClientId());
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
            Callable callable = new KurentoCallSession.RtpOfferProcessCallable(
                    fromId, callerCluserConnection.getMemberId());

            Future<String> processAnswer = (Future<String>)
                    clusterRpcService.submitTaskToMembers(callable, rtpOffer);
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
        sessionManager.addCallSession(rpcConnection.getClientId(), kurentoCallSession);
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

        /**
         * 判断caller是否和callee在同一台host上
         */
        JsonObject accetpObject = new JsonObject();
        /*告知calleer对方已经接听*/
        accetpObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_ACCEPT);
        if (media != null)
            accetpObject.addProperty(ProtocolElements.ONCALL_MEDIA_PARAM, media);
        if (callerRpcConnection != null) {
            //accetpObject.addProperty(ProtocolElements.ONCALL_SDPANSWER_PARAM, callerSdpAnswer);
            notificationService.sendNotification(
                    callerRpcConnection.getParticipantPrivateId(), ProtocolElements.ONCALL_METHOD, accetpObject);
        } else {
            notificationService.sendMemberNotification(
                    fromId, ProtocolElements.ONCALL_METHOD, accetpObject);
        }
    }

    private void onCallRejectProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM)
                .equals(ProtocolElements.ONCALL_EVENT_REJECT)) return;
        String fromId = getStringParam(request, ProtocolElements.ONCALL_FROMUSER_PARAM);

        if (!notificationService.connectionExist(fromId)) {
            //用户不存在
            return;
        }
        //判断caller是否还在线
        ClusterConnection callerCluserConnection =
                notificationService.getClusterConnection(fromId);

        RpcConnection callerRpcConnection =
                notificationService.getRpcConnection(callerCluserConnection.getSessionId());

        if (callerRpcConnection != null) {
            KurentoCallSession session =
                    sessionManager.removeCallSession(callerRpcConnection.getSessionId());
            if (session != null)
                session.release();
        } else {
            //远程要移除掉
        }

        JsonObject rejectObject = new JsonObject();
        if (request.getParams().has(ProtocolElements.ONCALL_EVENT_REJECT_REASON)) {
            rejectObject.addProperty(ProtocolElements.ONCALL_EVENT_REJECT_REASON, getStringParam(request,
                    ProtocolElements.ONCALL_EVENT_REJECT_REASON));
        }
        rejectObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM, ProtocolElements.ONCALL_EVENT_REJECT);
        notificationService.sendNotification(
                callerRpcConnection.getParticipantPrivateId(), ProtocolElements.ONCALL_METHOD, rejectObject);
    }

    private void onCallHangupProcess(RpcConnection rpcConnection,
                                     Request<JsonObject> request) {
        if (!getStringParam(request, ProtocolElements.ONCALL_EVENT_PARAM)
                .equals(ProtocolElements.ONCALL_EVENT_HANGUP)) return;
        KurentoCallSession kurentoCallSession =
                sessionManager.getCallSession(rpcConnection.getParticipantPrivateId());

        /*if (callSessions.containsKey(kurentoCallSession.getCallingFrom())) {
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
        }*/

        JsonObject hangupObject = new JsonObject();
        hangupObject.addProperty(ProtocolElements.ONCALL_EVENT_PARAM,
                ProtocolElements.ONCALL_EVENT_HANGUP);
        /**
         * 来自caller挂断
         */
        if (rpcConnection.getParticipantPrivateId().equals(kurentoCallSession.getCallingFrom())) {
            notificationService.sendNotification(
                    kurentoCallSession.getCallingTo(),
                    ProtocolElements.ONCALL_METHOD, hangupObject);
        } else {//来自callee挂断
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
                sessionManager.getCallSession(rpcConnection.getClientId());
        WebRtcEndpoint webRtcEndpoint =
                kurentoCallSession.getWebRtcEndpointById(rpcConnection.getClientId());
        webRtcEndpoint.addIceCandidate(new IceCandidate(candidate, sdpMid, sdpMLineIndex));
    }

}
