package org.ubonass.media.server.core;

import com.google.gson.JsonObject;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.client.CloudMediaException.Code;
import org.ubonass.media.server.config.CloudMediaConfig;
import org.ubonass.media.server.kurento.core.KurentoCallMediaStream;
import org.ubonass.media.server.utils.GeoLocation;
import org.ubonass.media.server.utils.RandomStringGenerator;

import javax.annotation.PostConstruct;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public abstract class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    /**
     * 用于管理1V1通信
     *
     * @Key:sessionId,每个MediaPipleline对应一次会话
     * @Value:为KurentoCallSession
     */
    private Map<String, KurentoCallMediaStream> callMediaStreams = new ConcurrentHashMap<>();

    /**
     * @Key: media sessionId
     * @Value:KurentoSession 为MediaSession的子类
     */
    protected ConcurrentMap<String, MediaSession> mediaSessions = new ConcurrentHashMap<>();
    /**
     * @Key: media sessionId
     * @Value:MediaSession 未使用的
     */
    protected ConcurrentMap<String, MediaSession> mediaSessionsNotActive = new ConcurrentHashMap<>();

    /**
     * @key：mediasession ID
     * @Value:ConcurrentHashMap<String, Participant> 该房间有哪些参与着
     * 客户端participantPublicId 客户端唯一ID号
     * 客户端对应的Participant
     */
    protected ConcurrentMap<String, ConcurrentHashMap<String, Participant>> sessionidParticipantpublicidParticipant = new ConcurrentHashMap<>();

    @Autowired
    protected CloudMediaConfig cloudMediaConfig;

    @Autowired
    protected SessionEventsHandler sessionEventsHandler;

    /*private static SessionManager context;

    public SessionManager() {
    }

    public static SessionManager getContext() {
        return context;
    }

    @PostConstruct
    public void init() {
        context = this;
    }*/

    /**
     * @param sessionId
     * @param callSession
     */
    public KurentoCallMediaStream addCallMediaStream(String sessionId,
                                                     KurentoCallMediaStream callSession) {
        KurentoCallMediaStream oldCallSession =
                callMediaStreams.putIfAbsent(sessionId, callSession);
        if (oldCallSession != null)
            logger.warn("callSession '{}' has just been added by another thread", sessionId);
        return oldCallSession;
    }

    /**
     * @param sessionId
     * @return
     */
    public KurentoCallMediaStream removeCallMediaStream(String sessionId) {
        KurentoCallMediaStream remove = null;
        if (callMediaStreams.containsKey(sessionId))
            remove = callMediaStreams.remove(sessionId);
        return remove;
    }

    /**
     * @param sessionId
     * @return
     */
    public KurentoCallMediaStream getCallMediaStream(String sessionId) {
        if (!callMediaStreams.containsKey(sessionId)) {
            logger.error("callMediaStreams not have {} value", sessionId);
            return null;
        }
        return callMediaStreams.get(sessionId);
    }

    public Participant newCallParticipant(String sessionId,
                                          String participantPrivatetId,
                                          String participantPublicId) {
        /*if (!sessionidParticipantpublicidParticipant
                .containsKey(sessionId)) {
            this.sessionidParticipantpublicidParticipant
                    .putIfAbsent(sessionId, new ConcurrentHashMap<>());
        }
        if (this.sessionidParticipantpublicidParticipant.get(sessionId) != null) {
            Participant p = new Participant(participantPrivatetId,
                    participantPublicId, sessionId, null, null, null, null, null);
            this.sessionidParticipantpublicidParticipant.get(sessionId).putIfAbsent(participantPublicId, p);
            return p;
        } else {
            throw new CloudMediaException(Code.ROOM_NOT_FOUND_ERROR_CODE, sessionId);
        }*/
        Participant p = new Participant(participantPrivatetId,
                participantPublicId, sessionId, null, null, null, null, null);
        return p;

    }

    public boolean mediaSessionExist(String sessionId) {

        return mediaSessions.containsKey(sessionId);
    }
    /**
     * Returns a Session given its id
     *
     * @return Session
     */
    public MediaSession getMediaSession(String sessionId) {
        return mediaSessions.get(sessionId);
    }

    /**
     * Returns all currently active (opened) sessions.
     *
     * @return set of the session's identifiers
     */
    public Collection<MediaSession> getMediaSessions() {
        return mediaSessions.values();
    }

    public MediaSession getMediaSessionNotActive(String sessionId) {
        return this.mediaSessionsNotActive.get(sessionId);
    }

    public Collection<MediaSession> getSessionsWithNotActive() {
        Collection<MediaSession> allSessions = new HashSet<>();
        allSessions.addAll(this.mediaSessionsNotActive.values().stream()
                .filter(sessionNotActive -> !mediaSessions.containsKey(sessionNotActive.getSessionId()))
                .collect(Collectors.toSet()));
        allSessions.addAll(this.getMediaSessions());
        return allSessions;
    }

    /**
     * Returns all the participants inside a session.
     *
     * @param sessionId identifier of the session
     * @return set of {@link Participant}
     * @throws CloudMediaException in case the session doesn't exist
     */
    public Set<Participant> getParticipants(String sessionId) throws CloudMediaException {
        MediaSession session = mediaSessions.get(sessionId);
        if (session == null) {
            throw new CloudMediaException(Code.ROOM_NOT_FOUND_ERROR_CODE, "Session '" + sessionId + "' not found");
        }
        Set<Participant> participants = session.getParticipants();
        participants.removeIf(p -> p.isClosed());
        return participants;
    }

    /**
     * Returns a participant in a session
     *
     * @param sessionId            identifier of the session
     * @param participantPrivateId private identifier of the participant
     * @return {@link Participant}
     * @throws CloudMediaException in case the session doesn't exist or the
     *                             participant doesn't belong to it
     */
    public Participant getParticipant(String sessionId, String participantPrivateId) throws CloudMediaException {
        MediaSession session = mediaSessions.get(sessionId);
        if (session == null) {
            throw new CloudMediaException(Code.ROOM_NOT_FOUND_ERROR_CODE, "Session '" + sessionId + "' not found");
        }
        Participant participant = session.getParticipantByPrivateId(participantPrivateId);
        if (participant == null) {
            throw new CloudMediaException(Code.USER_NOT_FOUND_ERROR_CODE,
                    "Participant '" + participantPrivateId + "' not found in session '" + sessionId + "'");
        }
        return participant;
    }

    /**
     * Returns a participant
     *
     * @param participantPrivateId private identifier of the participant
     * @return {@link Participant}
     * @throws CloudMediaException in case the participant doesn't exist
     */
    public Participant getParticipant(String participantPrivateId) throws CloudMediaException {
        for (MediaSession session : mediaSessions.values()) {
            if (!session.isClosed()) {
                if (session.getParticipantByPrivateId(participantPrivateId) != null) {
                    return session.getParticipantByPrivateId(participantPrivateId);
                }
            }
        }
        throw new CloudMediaException(Code.USER_NOT_FOUND_ERROR_CODE,
                "No participant with private id '" + participantPrivateId + "' was found");
    }


    public abstract void call(Participant participant, MediaOptions mediaOptions, Integer transactionId);

    public abstract void onCallAccept(Participant participant, MediaOptions mediaOptions, Integer transactionId);

    public abstract void onIceCandidate(Participant participant, String endpointName, String candidate,
                                        int sdpMLineIndex, String sdpMid, Integer transactionId);


    public abstract void evictParticipant(Participant evictedParticipant, Participant moderator, Integer transactionId,
                                          EndReason reason);

    public MediaOptions generateMediaOptions(Request<JsonObject> request) {
        return null;
    }
}
