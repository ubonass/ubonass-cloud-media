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
import java.util.Map;
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
     * @Value: ClusterSession
     * 当离开session的时候需要移除
     */
    private IMap<String, ClusterSession> clusterSessions;

    @Autowired
    private MediaSessionManager sessionManager;

    @Autowired
    private RpcNotificationService notificationService;

    private ClusterSessionManager clusterSessionManager;

    public ClusterRpcService(Config config) {
        this.config = config;
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

    public ClusterSessionManager getClusterSessionManager() {
        return clusterSessionManager;
    }

    public void setClusterSessionManager(ClusterSessionManager clusterSessionManager) {
        this.clusterSessionManager = clusterSessionManager;
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
        //logger.info("connection:{} ,add to clusterConnections map", connection.toString());
        ClusterConnection oldConnection =
                clusterConnections.putIfAbsent(connection.getParticipantPublicId(), connection);
        showConnections();
        return oldConnection;
    }

    public void showConnections() {
        Iterator<IMap.Entry<String, ClusterConnection>> entries =
                clusterConnections.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, ClusterConnection> entry = entries.next();

            logger.info("<{}, {}>",
                    entry.getKey(),entry.getValue().toString());
            //System.out.println("Key = " + entry.getKey() + ", Value = " + entry.getValue());
        }
        //logger.info("<ParticipantPublicId, ClusterConnection>: {}", this.clusterConnections.toString());
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

    /**
     * 根据publicid
     *
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
