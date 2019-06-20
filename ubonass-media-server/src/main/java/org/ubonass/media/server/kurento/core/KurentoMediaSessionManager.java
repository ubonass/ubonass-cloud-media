/*
 * (C) Copyright 2017-2019 OpenVidu (https://openvidu.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.ubonass.media.server.kurento.core;

import com.google.gson.JsonObject;
import com.hazelcast.core.IMap;
import org.kurento.client.*;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.java.client.*;
import org.ubonass.media.server.cluster.ClusterConnection;
import org.ubonass.media.server.cluster.ClusterRpcService;
import org.ubonass.media.server.cluster.ClusterSessionEvent;
import org.ubonass.media.server.core.*;
import org.ubonass.media.server.kurento.CloudMediaKurentoClientSessionInfo;
import org.ubonass.media.server.kurento.KurentoClientProvider;
import org.ubonass.media.server.kurento.KurentoClientSessionInfo;
import org.ubonass.media.server.kurento.KurentoFilter;
import org.ubonass.media.server.kurento.endpoint.SdpType;
import org.ubonass.media.server.rpc.RpcHandler;

import java.util.*;
import java.util.concurrent.*;

public class KurentoMediaSessionManager extends MediaSessionManager {

    private static final Logger log = LoggerFactory.getLogger(KurentoMediaSessionManager.class);

    @Autowired
    private KurentoClientProvider kcProvider;

    @Autowired
    private KurentoSessionEventsHandler kurentoSessionEventsHandler;

    @Autowired
    private KurentoParticipantEndpointConfig kurentoEndpointConfig;

    @Autowired
    private ClusterSessionEvent clusterSessionEvent;

    private KurentoClient kurentoClient;


    /**
     * 返回sdpAnswer
     *
     * @param participant
     * @param mediaOptions
     * @return
     */
    private String createAndProcessCallMediaStream(Participant participant,
                                                   MediaOptions mediaOptions, boolean remoteNeed) {
        String sessionId = participant.getSessionId();
        KurentoMediaSession kSession = (KurentoMediaSession) sessions.get(sessionId);
        if (kSession == null) {
            log.error("Session '{}' not found");
            throw new CloudMediaException(Code.ROOM_NOT_FOUND_ERROR_CODE, "Session '" + sessionId
                    + "' was not found, must be created before '" + sessionId + "' can call");
        }
        if (kSession.isClosed()) {
            log.error("'{}' is trying to join session '{}' but it is closing", participant.getParticipantPublicId(),
                    sessionId);
            throw new CloudMediaException(Code.ROOM_CLOSED_ERROR_CODE, "'" + participant.getParticipantPublicId()
                    + "' is trying to call session '" + sessionId + "' but it is closing");
        }
        //创建pipe和KurentoParticipant
        kSession.createCallMediaStream(participant, remoteNeed);

        KurentoMediaOptions kurentoOptions = (KurentoMediaOptions) mediaOptions;

        KurentoParticipant kParticipant =
                (KurentoParticipant)
                        kSession.getParticipantByPrivateId(participant.getParticipantPrivatetId());
        log.debug(
                "Request [Call_MEDIA] isOffer={} sdp={} "
                        + "loopbackAltSrc={} lpbkConnType={} doLoopback={} mediaElements={} ({})",
                kurentoOptions.isOffer, kurentoOptions.sdpOffer, kurentoOptions.loopbackAlternativeSrc,
                kurentoOptions.loopbackConnectionType, kurentoOptions.doLoopback, kurentoOptions.mediaElements,
                participant.getParticipantPublicId());

        SdpType sdpType = kurentoOptions.isOffer ? SdpType.OFFER : SdpType.ANSWER;

        /**
         * 如果remoteNeed为true,则会自动创建rtpEndpoint
         */
        kParticipant.createPublishingEndpoint(mediaOptions);
        if (remoteNeed)
            kParticipant.createRemotePublishingEndpoint(mediaOptions);

        //return kParticipant.startCallMediaStream(sdpType, kurentoOptions.sdpOffer, null);

        if (remoteNeed) {//将自身的rtpEndpoint->webrtcEndpoint
            kParticipant.getRemotePublisher().getEndpoint()
                    .connect(kParticipant.getPublisher().getEndpoint());
        }
        //同时将webrtcEndpoing->rtpEndpoint
        return kParticipant.publishToRoom(sdpType, kurentoOptions.sdpOffer, kurentoOptions.doLoopback,
                kurentoOptions.loopbackAlternativeSrc, kurentoOptions.loopbackConnectionType, remoteNeed);
    }


    private void createSessionIfNotExist(Participant participant) {
        String sessionId = participant.getSessionId();
        KurentoClientSessionInfo kcSessionInfo = new CloudMediaKurentoClientSessionInfo(
                participant.getParticipantPrivatetId(), sessionId);
        if (!sessions.containsKey(sessionId) && kcSessionInfo != null) {
            MediaSession sessionNotActive = new MediaSession(sessionId,
                    new SessionProperties.Builder().mediaMode(MediaMode.ROUTED)
                            .recordingMode(RecordingMode.ALWAYS)
                            .defaultRecordingLayout(RecordingLayout.BEST_FIT).build(),
                    cloudMediaConfig/*recordingManager*/);
            createSession(sessionNotActive, kcSessionInfo);
        }
    }

    /**
     * call support add by jeffrey
     *
     * @param participant   : callParticipant
     * @param mediaOptions
     * @param transactionId
     */
    @Override
    public void call(Participant participant, String calleeId, MediaOptions mediaOptions, Integer transactionId) {

        createSessionIfNotExist(participant);
        ClusterConnection calleeConnection =
                clusterRpcService.getConnection(calleeId);
        /**
         * 如果为true则不需要创建
         */
        boolean isLocal = clusterRpcService
                .isLocalHostMember(calleeConnection.getMemberId());

        String sdpAnswer = createAndProcessCallMediaStream(participant, mediaOptions, !isLocal);

        if (sdpAnswer == null) {
            CloudMediaException e = new CloudMediaException(Code.MEDIA_SDP_ERROR_CODE,
                    "Error generating SDP response for publishing user " + participant.getParticipantPublicId());
            log.error("PARTICIPANT {}: Error publishing media", participant.getParticipantPublicId(), e);
        }
        if (sdpAnswer != null) {
            sessionEventsHandler.onCall(participant, calleeId, sdpAnswer, transactionId);
        }
        //加入集群session
        clusterRpcService.joinSession(
                participant.getSessionId(), participant.getParticipantPublicId());

    }

    @Override
    public void onCallAccept(Participant participant,
                             String callerId,
                             MediaOptions mediaOptions,
                             Integer transactionId) {
        ClusterConnection callerConnection =
                clusterRpcService.getConnection(callerId);
        boolean isLocal =
                clusterRpcService.isLocalHostMember(callerConnection.getMemberId());
        if (!isLocal)//如果当前连接不在Caller服务器上需要再在远程创建一个连接
            createSessionIfNotExist(participant);

        String sdpAnswer = createAndProcessCallMediaStream(participant, mediaOptions, !isLocal);
        if (sdpAnswer == null) {
            CloudMediaException e = new CloudMediaException(Code.MEDIA_SDP_ERROR_CODE,
                    "Error generating SDP response for publishing user " + participant.getParticipantPublicId());
            log.error("PARTICIPANT {}: Error publishing media", participant.getParticipantPublicId(), e);
        }
        if (sdpAnswer != null) {
            sessionEventsHandler.onCallAccept(participant, sdpAnswer, transactionId);
        }
        /**
         * 将caller和call进行连接
         */
        KurentoMediaSession kSession = (KurentoMediaSession) sessions.get(participant.getSessionId());
        KurentoParticipant kParticipantCallee =
                (KurentoParticipant)
                        kSession.getParticipantByPrivateId(participant.getParticipantPrivatetId());
        /**
         * 寻找找出calleer
         */
        if (isLocal) {//callee连接在本host上
            log.info("...........start connect .........");
            KurentoParticipant kParticipantCaller =
                    (KurentoParticipant)
                            kSession.getParticipantByPrivateId(callerConnection.getParticipantPrivateId());

            kParticipantCallee.getPublisher().
                    connect(kParticipantCaller.getPublisher().getEndpoint());

            kParticipantCaller.getPublisher().
                    connect(kParticipantCallee.getPublisher().getEndpoint());

            /*kParticipantCallee.getPublisher().startTransmission();
            kParticipantCaller.getPublisher().startTransmission();*/

            log.info("...........end connect .........");
        } else {
            //当前rtpEndpoint生成sdpOffer,然后发送到目标机上,目标机接收到后开始进行处理
            clusterSessionEvent.publishToRoom(
                    kParticipantCallee.getSessionId(), callerId, kParticipantCallee.getRemotePublisher());
        }
        /**
         * 添加到集群
         */
        clusterRpcService.joinSession(
                participant.getSessionId(), participant.getParticipantPublicId());
    }

    /**
     * 据接应用于1对1服务
     *
     * @param sessionId
     * @param transactionId
     */
    @Override
    public void onCallReject(String sessionId, String callerId, Integer transactionId) {
        ClusterConnection callerConnection =
                clusterRpcService.getConnection(callerId);
        if (callerConnection == null) return;
        boolean callerRemote =
                clusterRpcService.isLocalHostMember(callerConnection.getMemberId());
        if (!callerRemote) {
            closeSession(sessionId, null);
        } else {
            //远程caller closeSession
            clusterSessionEvent.closeSession(sessionId, callerId);
        }
    }

    @Override
    public void onCallHangup(Participant participant, Integer transactionId) {
        //判断另一个connection是否在本机
        ClusterRpcService context = clusterRpcService;
        Collection<ClusterConnection> values =
                context.getSessionConnections(participant.getSessionId());

        for (ClusterConnection connection : values) {
            if (!context.isLocalHostMember(connection.getMemberId())) {
                clusterSessionEvent.closeSession(participant.getSessionId(),
                        connection.getParticipantPublicId());
            }
        }
        /**
         * 本服务器的媒体服务进行关闭
         */
        Set<Participant> existsParticipants = getParticipants(participant.getSessionId());
        sessionEventsHandler.onCallHangup(participant, existsParticipants, transactionId);
        Set<Participant> participants =
                closeSession(participant.getSessionId(), null);
    }


    @Override
    public void onIceCandidate(Participant participant, String endpointName, String candidate, int sdpMLineIndex, String sdpMid, Integer transactionId) {
        try {
            KurentoParticipant kParticipant = (KurentoParticipant) participant;
            log.debug("Request [ICE_CANDIDATE] endpoint={} candidate={} " + "sdpMLineIdx={} sdpMid={} ({})",
                    endpointName, candidate, sdpMLineIndex, sdpMid, participant.getParticipantPublicId());
            kParticipant.addIceCandidate(endpointName, new IceCandidate(candidate, sdpMid, sdpMLineIndex));
            sessionEventsHandler.onRecvIceCandidate(participant, transactionId, null);
        } catch (CloudMediaException e) {
            log.error("PARTICIPANT {}: Error receiving ICE " + "candidate (epName={}, candidate={})",
                    participant.getParticipantPublicId(), endpointName, candidate, e);
            sessionEventsHandler.onRecvIceCandidate(participant, transactionId, e);
        }
    }

    @Override
    public void leaveRoom(Participant participant, Integer transactionId, EndReason reason, boolean closeWebSocket) {
        log.debug("Request [LEAVE_ROOM] ({})", participant.getParticipantPublicId());

        KurentoParticipant kParticipant = (KurentoParticipant) participant;
        KurentoMediaSession session = kParticipant.getSession();
        String sessionId = session.getSessionId();

        if (session.isClosed()) {
            log.warn("'{}' is trying to leave from session '{}' but it is closing",
                    participant.getParticipantPublicId(), sessionId);
            throw new CloudMediaException(Code.ROOM_CLOSED_ERROR_CODE, "'" + participant.getParticipantPublicId()
                    + "' is trying to leave from session '" + sessionId + "' but it is closing");
        }
        session.leave(participant.getParticipantPrivatetId(), reason);

        // Update control data structures
        // modify by jeffrey
        if (sessionidParticipantpublicidParticipant.containsKey(sessionId)) {
            if (sessionidParticipantpublicidParticipant.get(sessionId) != null) {
                Participant p = sessionidParticipantpublicidParticipant.get(sessionId)
                        .remove(participant.getParticipantPublicId());

                /*if (this.coturnCredentialsService.isCoturnAvailable()) {
                    this.coturnCredentialsService.deleteUser(p.getToken().getTurnCredentials().getUsername());
                }

                if (sessionidTokenTokenobj.get(sessionId) != null) {
                    sessionidTokenTokenobj.get(sessionId).remove(p.getToken().getToken());
                }*/

                boolean stillParticipant = false;
                for (MediaSession s : sessions.values()) {
                    if (s.getParticipantByPrivateId(p.getParticipantPrivatetId()) != null) {
                        stillParticipant = true;
                        break;
                    }
                }
                /*if (!stillParticipant) {
                    insecureUsers.remove(p.getParticipantPrivatetId());
                }*/
            }
        }
        //modify by jeffrey
        showTokens();
        // Close Session if no more participants

        Set<Participant> remainingParticipants = null;
        try {
            remainingParticipants = getParticipants(sessionId);
        } catch (CloudMediaException e) {
            log.info("Possible collision when closing the session '{}' (not found)", sessionId);
            remainingParticipants = Collections.emptySet();
        }
        sessionEventsHandler.onParticipantLeft(participant, sessionId, remainingParticipants, transactionId, null, reason);

        //modify by jeffrey
        if (!EndReason.sessionClosedByServer.equals(reason)) {
            // If session is closed by a call to "DELETE /api/sessions" do NOT stop the
            // recording. Will be stopped after in method
            // "SessionManager.closeSessionAndEmptyCollections"
            if (remainingParticipants.isEmpty()) {
                //modify by jeffrey
                /*if (cloudMediaConfig.isRecordingModuleEnabled()
                        && MediaMode.ROUTED.equals(session.getSessionProperties().mediaMode())
                        && (this.recordingManager.sessionIsBeingRecorded(sessionId))) {
                    // Start countdown to stop recording. Will be aborted if a Publisher starts
                    // before timeout
                    log.info(
                            "Last participant left. Starting {} seconds countdown for stopping recording of session {}",
                            this.cloudMediaConfig.getOpenviduRecordingAutostopTimeout(), sessionId);
                    recordingManager.initAutomaticRecordingStopThread(session);
                } else*/
                {
                    log.info("No more participants in session '{}', removing it and closing it", sessionId);
                    this.closeSessionAndEmptyCollections(session, reason);
                    showTokens();
                }
                //modify by jeffrey
            } /*else if (remainingParticipants.size() == 1 && cloudMediaConfig.isRecordingModuleEnabled()
                    && MediaMode.ROUTED.equals(session.getSessionProperties().mediaMode())
                    && this.recordingManager.sessionIsBeingRecorded(sessionId)
                    && ProtocolElements.RECORDER_PARTICIPANT_PUBLICID
                    .equals(remainingParticipants.iterator().next().getParticipantPublicId())) {
                // Start countdown
                log.info("Last participant left. Starting {} seconds countdown for stopping recording of session {}",
                        this.cloudMediaConfig.getOpenviduRecordingAutostopTimeout(), sessionId);
                recordingManager.initAutomaticRecordingStopThread(session);
            }*/
        }

        // Finally close websocket session if required
        if (closeWebSocket) {
            sessionEventsHandler.closeRpcSession(participant.getParticipantPrivatetId());
        }
    }

    @Override
    public void evictParticipant(Participant evictedParticipant, Participant moderator, Integer transactionId, EndReason reason) {
        if (evictedParticipant != null) {
            KurentoParticipant kParticipant = (KurentoParticipant) evictedParticipant;
            Set<Participant> participants = kParticipant.getSession().getParticipants();
            this.leaveRoom(kParticipant, null, reason, false);
            this.sessionEventsHandler.onForceDisconnect(moderator, evictedParticipant,
                    participants, transactionId, null, reason);
            //modify by jeffrey we needn't closeRpcSession
            //sessionEventsHandler.closeRpcSession(evictedParticipant.getParticipantPrivatetId());
        } else {
            if (moderator != null && transactionId != null) {
                this.sessionEventsHandler.onForceDisconnect(moderator, evictedParticipant,
                        new HashSet<>(Arrays.asList(moderator)), transactionId,
                        new CloudMediaException(Code.USER_NOT_FOUND_ERROR_CODE,
                                "Connection not found when calling 'forceDisconnect'"),
                        null);
            }
        }
    }


    /**
     * Creates a session if it doesn't already exist. The session's id will be
     * indicated by the session info bean.
     *
     * @param kcSessionInfo bean that will be passed to the
     *                      {@link KurentoClientProvider} in order to obtain the
     *                      {@link KurentoClient} that will be used by the room
     * @throws CloudMediaException in case of error while creating the session
     */
    public void createSession(MediaSession sessionNotActive, KurentoClientSessionInfo kcSessionInfo)
            throws CloudMediaException {
        String sessionId = kcSessionInfo.getRoomName();
        KurentoMediaSession session = (KurentoMediaSession) sessions.get(sessionId);
        if (session != null) {
            throw new CloudMediaException(Code.ROOM_CANNOT_BE_CREATED_ERROR_CODE,
                    "Session '" + sessionId + "' already exists");
        }
        this.kurentoClient = kcProvider.getKurentoClient(kcSessionInfo);
        session = new KurentoMediaSession(sessionNotActive, kurentoClient, kurentoSessionEventsHandler,
                kurentoEndpointConfig, kcProvider.destroyWhenUnused());

        KurentoMediaSession oldSession = (KurentoMediaSession) sessions.putIfAbsent(sessionId, session);
        if (oldSession != null) {
            log.warn("Session '{}' has just been created by another thread", sessionId);
            return;
        }
        String kcName = "[NAME NOT AVAILABLE]";
        if (kurentoClient.getServerManager() != null) {
            kcName = kurentoClient.getServerManager().getName();
        }
        log.warn("No session '{}' exists yet. Created one using KurentoClient '{}'.", sessionId, kcName);

        sessionEventsHandler.onSessionCreated(session);

    }


    @Override
    public KurentoMediaOptions generateMediaOptions(Request<JsonObject> request) throws CloudMediaException {

        String sdpOffer = RpcHandler.getStringParam(request, ProtocolElements.PUBLISHVIDEO_SDPOFFER_PARAM);
        boolean hasAudio = RpcHandler.getBooleanParam(request, ProtocolElements.PUBLISHVIDEO_HASAUDIO_PARAM);
        boolean hasVideo = RpcHandler.getBooleanParam(request, ProtocolElements.PUBLISHVIDEO_HASVIDEO_PARAM);

        Boolean audioActive = null, videoActive = null;
        String typeOfVideo = null, videoDimensions = null;
        Integer frameRate = null;
        KurentoFilter kurentoFilter = null;

        try {
            audioActive = RpcHandler.getBooleanParam(request, ProtocolElements.PUBLISHVIDEO_AUDIOACTIVE_PARAM);
        } catch (RuntimeException noParameterFound) {
        }
        try {
            videoActive = RpcHandler.getBooleanParam(request, ProtocolElements.PUBLISHVIDEO_VIDEOACTIVE_PARAM);
        } catch (RuntimeException noParameterFound) {
        }
        try {
            typeOfVideo = RpcHandler.getStringParam(request, ProtocolElements.PUBLISHVIDEO_TYPEOFVIDEO_PARAM);
        } catch (RuntimeException noParameterFound) {
        }
        try {
            videoDimensions = RpcHandler.getStringParam(request, ProtocolElements.PUBLISHVIDEO_VIDEODIMENSIONS_PARAM);
        } catch (RuntimeException noParameterFound) {
        }
        try {
            frameRate = RpcHandler.getIntParam(request, ProtocolElements.PUBLISHVIDEO_FRAMERATE_PARAM);
        } catch (RuntimeException noParameterFound) {
        }
        try {
            JsonObject kurentoFilterJson = (JsonObject) RpcHandler.getParam(request,
                    ProtocolElements.PUBLISHVIDEO_KURENTOFILTER_PARAM);
            if (kurentoFilterJson != null) {
                try {
                    kurentoFilter = new KurentoFilter(kurentoFilterJson.get("type").getAsString(),
                            kurentoFilterJson.get("options").getAsJsonObject());
                } catch (Exception e) {
                    throw new CloudMediaException(Code.FILTER_NOT_APPLIED_ERROR_CODE,
                            "'filter' parameter wrong:" + e.getMessage());
                }
            }
        } catch (CloudMediaException e) {
            throw e;
        } catch (RuntimeException noParameterFound) {
        }
        boolean doLoopback = false;
        try {
            doLoopback = RpcHandler.getBooleanParam(request, ProtocolElements.PUBLISHVIDEO_DOLOOPBACK_PARAM);
        } catch (RuntimeException noParameterFound) {
        }

        return new KurentoMediaOptions(true, sdpOffer, null, null, hasAudio, hasVideo, audioActive, videoActive,
                typeOfVideo, frameRate, videoDimensions, kurentoFilter, doLoopback);
    }

}
