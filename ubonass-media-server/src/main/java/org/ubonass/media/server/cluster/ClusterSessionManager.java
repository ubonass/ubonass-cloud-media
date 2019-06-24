package org.ubonass.media.server.cluster;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ubonass.media.client.CloudMediaException;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;

public class ClusterSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(ClusterSessionManager.class);

    private ClusterRpcService clusterRpcService;

    private HazelcastInstance hazelcastInstance;
    /**
     * 用于管理有mediaSession的用户
     * 该集合用于管理处于激活状态的session
     * @Key:mediaSessionId
     * @Value: ClusterSession
     * 当离开session的时候需要移除
     */
    private IMap<String, ClusterSession> clusterSessions;
    /**
     * 用于管理空房间
     * @Key:mediaSessionId
     * @Value: ClusterSession
     */
    private IMap<String, ClusterSession> clusterSessionsNotActive;

    public ClusterSessionManager(ClusterRpcService clusterRpcService) {
        this.clusterRpcService = clusterRpcService;
    }

    @PostConstruct
    public void init(){
        this.hazelcastInstance = clusterRpcService.getHazelcastInstance();
        this.clusterSessions = hazelcastInstance.getMap("clusterSessions");
        this.clusterSessionsNotActive = hazelcastInstance.getMap("clusterSessionsNotActive");
        this.clusterRpcService.setClusterSessionManager(this);
    }

    /**
     * 获取一个空房间
     * @param sessionId
     * @return
     */
    public ClusterSession getSessionNotActive(String sessionId) {
        return this.clusterSessionsNotActive.get(sessionId);
    }
    /**
     * 获取空房间集合
     * @return
     */
    public Collection<ClusterSession> getSessionsWithNotActive() {
        Collection<ClusterSession> allSessions = new HashSet<>();
        allSessions.addAll(this.clusterSessionsNotActive.values().stream()
                .filter(sessionNotActive -> !clusterSessions.containsKey(sessionNotActive.getSessionName()))
                .collect(Collectors.toSet()));
        allSessions.addAll(this.getSessions());
        return allSessions;
    }

    /**
     * 将一个空房间插入到集合,当加入房间的时候需要该ID从该集合中清除
     * @param sessionId
     * @return
     */
    public ClusterSession storeSessionNotActive(String sessionId) {
        ClusterSession sessionNotActive = new ClusterSession(sessionId);
        this.clusterSessionsNotActive.put(sessionId, sessionNotActive);
        return sessionNotActive;
    }
    /**
     * 根据会话和在会话中的客户端获取该客户端是连接到那台主机
     * @param sessionId
     * @param participantPublicId
     * @return
     */
    public String getConnectionMemberId(String sessionId, String participantPublicId) {
        ClusterConnection connection = this.getConnection(sessionId, participantPublicId);
        if (connection != null)
            return connection.getMemberId();
        else
            return null;
    }


    public Collection<ClusterSession> getSessions() {
        return clusterSessions.values();
    }

    public ClusterSession getSession(String sessionId) {
        if (clusterSessions.containsKey(sessionId)) {
            return clusterSessions.get(sessionId);
        } else {
            throw new CloudMediaException(CloudMediaException.Code.ROOM_NOT_FOUND_ERROR_CODE,
                    "sessionId : {" + sessionId + "}  not Exist in local and remote member");
        }
    }

    /**
     * 如果已经有会话
     *
     * @param sessionId
     * @param participantPublicId
     * @return
     */
    public ClusterConnection getConnection(String sessionId, String participantPublicId) {
        if (clusterSessions.containsKey(sessionId) && clusterSessions.get(sessionId) != null) {
            ClusterSession session = clusterSessions.get(sessionId);
            if (hazelcastInstance
                    .getMap(session.getSessionName()).containsKey(participantPublicId)) {
                return (ClusterConnection) hazelcastInstance
                        .getMap(session.getSessionName()).get(participantPublicId);
            } else {
                throw new CloudMediaException(CloudMediaException.Code.TRANSPORT_ERROR_CODE,
                        "participantPublicId : {" + participantPublicId + "} connection not Exist in local and remote member");
            }
        } else {
            throw new CloudMediaException(CloudMediaException.Code.ROOM_NOT_FOUND_ERROR_CODE,
                    "sessionId : {" + sessionId + "}  not Exist in local and remote member");
        }
    }

    /**
     * 将session进行集群管理
     */
    public void joinSession(String sessionId, String participantPublicId) {
        ClusterSession session = null;
        if (!clusterSessions.containsKey(sessionId)) {
            session = new ClusterSession(sessionId);
            clusterSessions.putIfAbsent(sessionId, session);
        } else {
            session = clusterSessions.get(sessionId);
        }
        if (session != null) {
            ClusterConnection connection =
                    clusterRpcService.getConnection(participantPublicId);
            if (connection != null) {
                connection.setSessionId(session.getSessionName());
                /**
                 * 这里引用ClusterConnection不是再创建一个
                 */
                hazelcastInstance.getMap(session.getSessionName())
                        .putIfAbsent(participantPublicId, connection);
                showSessions();
            } else {
                throw new CloudMediaException(CloudMediaException.Code.TRANSPORT_ERROR_CODE,
                        "participantPublicId : {" + participantPublicId + "} connection not Exist in local and remote member");
            }
        }
    }

    public void showSessions() {
        Iterator<IMap.Entry<String, ClusterSession>> entries =
                clusterSessions.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, ClusterSession> entry = entries.next();
            ClusterSession session = entry.getValue();
            IMap<String, ClusterConnection> childMap =
                    hazelcastInstance.getMap(session.getSessionName());
            for (ClusterConnection connection : childMap.values())
                logger.info("<{}, {}>", session.getSessionName(),connection.toString());
            //System.out.println("Key = " + entry.getKey() + ", Value = " + entry.getValue());
        }
    }

    public Collection<ClusterConnection> getSessionConnections(String sessionId) {
        if (clusterSessions.containsKey(sessionId)) {
            ClusterSession session = clusterSessions.get(sessionId);
            IMap<String, ClusterConnection> clusterSessionConnections =
                    hazelcastInstance.getMap(session.getSessionName());
            Collection<ClusterConnection> values = clusterSessionConnections.values();
            return values;
        } else {
            throw new CloudMediaException(CloudMediaException.Code.ROOM_NOT_FOUND_ERROR_CODE,
                    "sessionId : {" + sessionId + "}  not Exist in local and remote member");
        }
    }

    public void closeSession(String sessionId) {
        if (clusterSessions.containsKey(sessionId)) {
            ClusterSession session =
                    clusterSessions.remove(sessionId);
            IMap<String, ClusterConnection> clusterSessionConnections =
                    hazelcastInstance.getMap(sessionId);
            clusterSessionConnections.clear();
            session = null;
        }
    }

    /**
     * @param sessionId
     * @param participantPublicId
     */
    public void leaveSession(String sessionId, String participantPublicId) {
        if (clusterSessions.containsKey(sessionId)) {
            ClusterSession session =
                    clusterSessions.get(sessionId);
            IMap<String, ClusterConnection> clusterSessionConnections =
                    hazelcastInstance.getMap(session.getSessionName());
            if (clusterSessionConnections
                    .containsKey(participantPublicId)) {
                clusterSessionConnections.remove(participantPublicId);
            }
        }
    }

}
