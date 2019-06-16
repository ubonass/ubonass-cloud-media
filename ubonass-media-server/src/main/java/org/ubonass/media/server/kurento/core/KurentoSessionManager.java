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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.kurento.client.*;
import org.kurento.jsonrpc.Props;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.client.internal.ProtocolElements;
import org.ubonass.media.java.client.*;
import org.ubonass.media.server.core.*;
import org.ubonass.media.server.kurento.CloudMediaKurentoClientSessionInfo;
import org.ubonass.media.server.kurento.KurentoClientProvider;
import org.ubonass.media.server.kurento.KurentoClientSessionInfo;
import org.ubonass.media.server.kurento.KurentoFilter;
import org.ubonass.media.server.kurento.endpoint.PublisherEndpoint;
import org.ubonass.media.server.kurento.endpoint.SdpType;
import org.ubonass.media.server.rpc.RpcHandler;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class KurentoSessionManager extends SessionManager {

    private static final Logger log = LoggerFactory.getLogger(KurentoSessionManager.class);

    @Autowired
    private KurentoClientProvider kcProvider;

    @Autowired
    private KurentoSessionEventsHandler kurentoSessionEventsHandler;

    @Autowired
    private KurentoParticipantEndpointConfig kurentoEndpointConfig;

    private KurentoClient kurentoClient;


    /**
     * 返回sdpAnswer
     *
     * @param participant
     * @param mediaOptions
     * @return
     */
    private String createAndProcessCallMediaStream(Participant participant,
                                                   MediaOptions mediaOptions) {
        String sessionId = participant.getSessionId();
        KurentoSession kSession = (KurentoSession) mediaSessions.get(sessionId);
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
        kSession.createCallMediaStream(participant);


        KurentoMediaOptions kurentoOptions = (KurentoMediaOptions) mediaOptions;

        KurentoParticipant kParticipant =
                (KurentoParticipant)
                        kSession.getParticipantByPrivateId(participant.getParticipantPrivatetId());
        log.info(
                "Request [Call_MEDIA] isOffer={} sdp={} "
                        + "loopbackAltSrc={} lpbkConnType={} doLoopback={} mediaElements={} ({})",
                kurentoOptions.isOffer, kurentoOptions.sdpOffer, kurentoOptions.loopbackAlternativeSrc,
                kurentoOptions.loopbackConnectionType, kurentoOptions.doLoopback, kurentoOptions.mediaElements,
                participant.getParticipantPublicId());

        SdpType sdpType = kurentoOptions.isOffer ? SdpType.OFFER : SdpType.ANSWER;

        kParticipant.createPublishingEndpoint(mediaOptions);

        return kParticipant.startCallMediaStream(sdpType, kurentoOptions.sdpOffer, null);
    }

    /**
     * call support add by jeffrey
     *
     * @param participant   : callParticipant
     * @param mediaOptions
     * @param transactionId
     */
    @Override
    public void call(Participant participant, MediaOptions mediaOptions, Integer transactionId) {
        String sessionId = participant.getSessionId();
        KurentoClientSessionInfo kcSessionInfo = new CloudMediaKurentoClientSessionInfo(
                participant.getParticipantPrivatetId(), sessionId);
        if (!mediaSessions.containsKey(sessionId) && kcSessionInfo != null) {
            MediaSession sessionNotActive = new MediaSession(sessionId,
                    new SessionProperties.Builder().mediaMode(MediaMode.ROUTED)
                            .recordingMode(RecordingMode.ALWAYS)
                            .defaultRecordingLayout(RecordingLayout.BEST_FIT).build(),
                    cloudMediaConfig/*recordingManager*/);
            createSession(sessionNotActive, kcSessionInfo);
        }

        String sdpAnswer = createAndProcessCallMediaStream(participant, mediaOptions);

        if (sdpAnswer == null) {
            CloudMediaException e = new CloudMediaException(Code.MEDIA_SDP_ERROR_CODE,
                    "Error generating SDP response for publishing user " + participant.getParticipantPublicId());
            log.error("PARTICIPANT {}: Error publishing media", participant.getParticipantPublicId(), e);
        }
        if (sdpAnswer != null) {
            sessionEventsHandler.onCallResponse(participant, sdpAnswer, transactionId);
        }
    }

    @Override
    public void onCallAccept(Participant participant,
                             MediaOptions mediaOptions,
                             Integer transactionId) {
        String sdpAnswer = createAndProcessCallMediaStream(participant, mediaOptions);
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
        KurentoSession kSession = (KurentoSession)
                mediaSessions.get(participant.getSessionId());
        KurentoParticipant kParticipantCallee =
                (KurentoParticipant)
                        kSession.getParticipantByPrivateId(participant.getParticipantPrivatetId());
        KurentoParticipant kParticipantCaller = null;
        /**
         * 寻找找出calleer
         */
        Set<Participant> participants = kParticipantCallee.getSession().getParticipants();
        log.info("participants number {}", participants.size());
        for (Participant p : participants) {
            if (p.getParticipantPrivatetId().equals(participant.getParticipantPrivatetId())) {
                continue;
            } else {
                kParticipantCaller = (KurentoParticipant) p;
                log.info("kParticipantCaller.kParticipantCaller {}",
                        kParticipantCaller.getParticipantPublicId());
            }
        }
        WebRtcEndpoint calleeWebRtcEndpoint =
                (WebRtcEndpoint) kParticipantCallee.getCallMediaStream().getEndpoint();
        WebRtcEndpoint callerWebRtcEndpoint =
                (WebRtcEndpoint) kParticipantCaller.getCallMediaStream().getEndpoint();
        calleeWebRtcEndpoint.connect(callerWebRtcEndpoint);
        callerWebRtcEndpoint.connect(calleeWebRtcEndpoint);
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
        KurentoSession session = (KurentoSession) mediaSessions.get(sessionId);
        if (session != null) {
            throw new CloudMediaException(Code.ROOM_CANNOT_BE_CREATED_ERROR_CODE,
                    "Session '" + sessionId + "' already exists");
        }
        this.kurentoClient = kcProvider.getKurentoClient(kcSessionInfo);
        session = new KurentoSession(sessionNotActive, kurentoClient, kurentoSessionEventsHandler,
                kurentoEndpointConfig, kcProvider.destroyWhenUnused());

        KurentoSession oldSession = (KurentoSession) mediaSessions.putIfAbsent(sessionId, session);
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
