package org.ubonass.media.server.cluster;

import com.hazelcast.config.Config;
import com.hazelcast.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.server.core.MediaSessionManager;
import org.ubonass.media.server.rpc.RpcConnection;
import org.ubonass.media.server.rpc.RpcNotificationService;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class ClusterRpcService {

    private static final Logger logger = LoggerFactory.getLogger(ClusterRpcService.class);

    private HazelcastInstance hazelcastInstance;

    private String memberId;

    private IExecutorService executorService;

    private Config config;

    private static ClusterRpcService context;

    /**
     * key为用户远程连的客户唯一标识,Value为ClusterConnection,针对所有集群
     */
    private IMap<String, ClusterConnection> clusterConnections;
    /**
     * 用于管理有mediaSession的用户
     *
     * @Key:mediaSessionId
     * @Value: @Key:ClientId,@Value:ClusterConnection
     * 当离开session的时候需要移除
     */
    private IMap<String, ClusterSession> clusterSessions;

    @Autowired
    private MediaSessionManager sessionManager;

    @Autowired
    private RpcNotificationService notificationService;

    public ClusterRpcService(Config config/*,
                             RpcNotificationService notificationService,
                             MediaSessionManager sessionManager*/) {
        this.config = config;
        /*this.sessionManager = sessionManager;
        this.notificationService = notificationService;*/
        this.config.setInstanceName("hazelcast-instance");
        hazelcastInstance = Hazelcast.newHazelcastInstance(this.config);
        memberId = hazelcastInstance.getCluster().getLocalMember().getUuid();
        logger.info("this uuid is {}", memberId);
        executorService =
                hazelcastInstance.getExecutorService("streamsConnector");

        clusterConnections =
                hazelcastInstance.getMap("clusterConnections");
        clusterSessions =
                hazelcastInstance.getMap("clusterSessions");
        context = this;
    }

    public static ClusterRpcService getContext() {
        return context;
    }

    public MediaSessionManager getSessionManager() {
        return sessionManager;
    }

    public RpcNotificationService getRpcNotificationService() {
        return notificationService;
    }

    /**
     * 根据clientId获取在线用户
     *
     * @return
     */
    public IMap<String, ClusterConnection> getConnections() {
        return clusterConnections;
    }

    /**
     * 根据房间号获取当前房间中的ClusterConnection
     *
     * @return
     */
    public IMap<String, ClusterSession> getSessionsMap() {
        return clusterSessions;
    }

    /**
     * 判断远程连接是否存在
     *
     * @param participantPublicId
     * @return
     */
    public boolean connectionExist(String participantPublicId) {
        return clusterConnections.containsKey(participantPublicId);
    }

    public ClusterConnection closeConnection(String participantPublicId) {
        if (!clusterConnections.containsKey(participantPublicId)) return null;
        ClusterConnection clusterConnection = clusterConnections.remove(participantPublicId);
        if (clusterConnection == null) {
            logger.error("No connection found for public id {}, unable to cleanup", participantPublicId);
            return null;
        }
        return clusterConnection;
    }

    /**
     * 返回null表示成功
     *
     * @param rpcConnection
     * @return
     */
    public ClusterConnection addConnection(RpcConnection rpcConnection) {
        if (rpcConnection == null) return null;
        ClusterConnection connection = new ClusterConnection(
                rpcConnection.getParticipantPublicId(),
                rpcConnection.getParticipantPrivateId(),
                rpcConnection.getMemberId());
        ClusterConnection oldConnection =
                clusterConnections.putIfAbsent(connection.getParticipantPublicId(), connection);
        return oldConnection;
    }

    /**
     * 从clusterConnections集合中根据participantPublicId获取ClusterConnection
     *
     * @param participantPublicId
     * @return
     */
    public ClusterConnection getConnection(String participantPublicId) {
        if (clusterConnections.containsKey(participantPublicId)) {
            return clusterConnections.get(participantPublicId);
        } else {
            throw new CloudMediaException(CloudMediaException.Code.TRANSPORT_ERROR_CODE,
                    "participantPublicId : {" + participantPublicId + "} connection not Exist in local and remote member");
        }
    }

    public String getConnectionMemberId(String participantPublicId) {
        ClusterConnection connection = getConnection(participantPublicId);
        if (connection != null)
            return connection.getMemberId();
        else
            return null;
    }

    public String getConnectionMemberId(String sessionId, String participantPublicId) {
        ClusterConnection connection =
                getConnection(sessionId, participantPublicId);
        if (connection != null)
            return connection.getMemberId();
        else
            return null;
    }

    /**
     * 如果已经有会话
     *
     * @param sessionId
     * @param participantPublicId
     * @return
     */
    public ClusterConnection getConnection(String sessionId, String participantPublicId) {
        if (clusterSessions.containsKey(sessionId)
                && clusterSessions.get(sessionId) != null) {
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
                    clusterConnections.get(participantPublicId);
            if (connection != null) {
                connection.setSessionId(session.getSessionName());
                /**
                 * 这里引用ClusterConnection不是再创建一个
                 */
                hazelcastInstance.getMap(session.getSessionName())
                        .putIfAbsent(participantPublicId, connection);
            } else {
                throw new CloudMediaException(CloudMediaException.Code.TRANSPORT_ERROR_CODE,
                        "participantPublicId : {" + participantPublicId + "} connection not Exist in local and remote member");
            }
        }
    }

    public Collection<ClusterConnection> getSessionConnections(String sessionId) {
        if (clusterSessions.containsKey(sessionId)) {
            ClusterSession session = clusterSessions.get(sessionId);
            IMap<String, ClusterConnection> clusterSessionConnections =
                    hazelcastInstance.getMap(session.getSessionName());
            Collection<ClusterConnection> values =
                    clusterSessionConnections.values();
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

    /**
     * 根据publicid
     * @param memberId
     * @return
     */
    public boolean isLocalHostMember(String memberId) {
        if (memberId == null) return false;
        return memberId.equals(this.memberId);
    }

    public HazelcastInstance getHazelcastInstance() {
        return hazelcastInstance;
    }

    public String getMemberId() {
        return memberId;
    }

    public Future<?> submitTask(Callable<?> callable) {
        return executorService.submit(callable);
    }

    public Future<?> submitTaskToMembers(Callable<?> callable, MemberSelector selector) {
        return executorService.submit(callable, selector);
    }

    public Future<?> submitTaskToMembers(Callable<?> callable, String memberId) {
        MemberSelector selector = new MemberSelector() {
            @Override
            public boolean select(Member member) {
                return member.getUuid().equals(memberId);
            }
        };
        return submitTaskToMembers(callable, selector);
    }

    public void ececuteTask(Runnable runnable) {
        executorService.submit(runnable);
    }


    public void executeToMembers(Runnable runnable, MemberSelector selector) {
        executorService.executeOnMembers(runnable, selector);
    }

    public void executeToMember(Runnable runnable, String memberId) {
        Iterator<Member> iter =
                hazelcastInstance.getCluster().getMembers().iterator();
        while (iter.hasNext()) {
            Member member = iter.next();
            if (member.getUuid().equals(memberId)) {
                executorService.executeOnMember(runnable, member);
            }
        }
    }
}
