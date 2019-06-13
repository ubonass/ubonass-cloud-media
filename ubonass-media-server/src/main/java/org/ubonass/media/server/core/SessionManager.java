package org.ubonass.media.server.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ubonass.media.server.kurento.core.KurentoCallMediaStream;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    /**
     * 用于管理1V1通信
     *
     * @Key:sessionId,每个MediaPipleline对应一次会话
     * @Value:为KurentoCallSession
     */
    private Map<String, KurentoCallMediaStream> callMediaStreams = new ConcurrentHashMap<>();

    private static SessionManager context;

    private SessionManager() { }

    public static SessionManager getContext() {
        return context;
    }

    @PostConstruct
    public void init() {
        context = this;
    }

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
}
