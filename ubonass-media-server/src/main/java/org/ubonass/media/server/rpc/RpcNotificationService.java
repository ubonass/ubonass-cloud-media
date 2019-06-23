

package org.ubonass.media.server.rpc;

import com.google.gson.JsonObject;
import com.hazelcast.core.IMap;
import org.kurento.jsonrpc.Session;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.server.cluster.ClusterConnection;
import org.ubonass.media.server.cluster.ClusterRpcService;
import org.ubonass.media.server.cluster.ClusterRpcNotification;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RpcNotificationService {

    private static final Logger log = LoggerFactory.getLogger(RpcNotificationService.class);

    private ConcurrentMap<String, RpcConnection> rpcConnections = new ConcurrentHashMap<>();

    @Autowired
    private ClusterRpcService clusterRpcService;

    public RpcConnection newRpcConnection(Transaction t, Request<JsonObject> request) {
        String participantPrivateId = t.getSession().getSessionId();
        RpcConnection connection = new RpcConnection(t.getSession());
        RpcConnection oldConnection = rpcConnections.putIfAbsent(participantPrivateId, connection);
        if (oldConnection != null) {
            log.warn("Concurrent initialization of rpcSession #{}", participantPrivateId);
            connection = oldConnection;
        }
        return connection;
    }

    /*public ClusterConnection newClusterConnection(RpcConnection rpcConnection) {
        if (rpcConnection == null) return null;
        ClusterConnection connection = new ClusterConnection(
                rpcConnection.getClientId(),
                rpcConnection.getParticipantPrivateId(),
                rpcConnection.getMemberId());
        ClusterConnection oldConnection =
                clusterRpcService
                        .getConnections().putIfAbsent(rpcConnection.getClientId(), connection);
        if (oldConnection != null) {
            log.warn("Concurrent initialization of rpcSession #{}", rpcConnection.getClientId());
            connection = oldConnection;
        }
        return connection;
    }*/

    /**
     * @param connection
     * @return
     */
    public RpcConnection addRpcConnection(RpcConnection connection) {
        if (connection == null) return null;
        RpcConnection oldConnection =
                rpcConnections.putIfAbsent(connection.getParticipantPrivateId(), connection);
        if (oldConnection != null) {
            log.warn("Concurrent initialization of rpcSession #{}", connection.getSessionId());
            connection = oldConnection;
        }
        return connection;
    }

    public RpcConnection addTransaction(Transaction t, Request<JsonObject> request) {
        String participantPrivateId = t.getSession().getSessionId();
        RpcConnection connection = rpcConnections.get(participantPrivateId);
        connection.addTransaction(request.getId(), t);
        return connection;
    }

    public void sendResponse(String participantPrivateId, Integer transactionId, Object result) {
        Transaction t = getAndRemoveTransaction(participantPrivateId, transactionId);
        if (t == null) {
            log.error("No transaction {} found for paticipant with private id {}, unable to send result {}",
                    transactionId, participantPrivateId, result);
            return;
        }
        try {
            t.sendResponse(result);
        } catch (Exception e) {
            log.error("Exception responding to participant ({})", participantPrivateId, e);
        }
    }

    public void sendErrorResponse(String participantPrivateId, Integer transactionId, Object data,
                                  CloudMediaException error) {
        Transaction t = getAndRemoveTransaction(participantPrivateId, transactionId);
        if (t == null) {
            log.error("No transaction {} found for paticipant with private id {}, unable to send result {}",
                    transactionId, participantPrivateId, data);
            return;
        }
        try {
            String dataVal = data != null ? data.toString() : null;
            t.sendError(error.getCodeValue(), error.getMessage(), dataVal);
        } catch (Exception e) {
            log.error("Exception sending error response to user ({})", transactionId, e);
        }
    }

    public void sendNotification(final String participantPrivateId, final String method, final Object params) {
        RpcConnection rpcSession = rpcConnections.get(participantPrivateId);
        if (rpcSession == null || rpcSession.getSession() == null) {
            log.error("No rpc session found for private id {}, unable to send notification {}: {}",
                    participantPrivateId, method, params);
            return;
        }
        Session s = rpcSession.getSession();

        try {
            if (params != null)
                s.sendNotification(method, params);
            else
                s.sendNotification(method);
        } catch (Exception e) {
            log.error("Exception sending notification '{}': {} to participant with private id {}", method, params,
                    participantPrivateId, e);
        }
    }


    public RpcConnection closeRpcSession(String participantPrivateId) {
        RpcConnection rpcSession = rpcConnections.remove(participantPrivateId);
        if (rpcSession == null || rpcSession.getSession() == null) {
            log.error("No session found for private id {}, unable to cleanup", participantPrivateId);
            return null;
        }
        Session s = rpcSession.getSession();
        try {
            s.close();
            log.info("Closed session for participant with private id {}", participantPrivateId);
            this.showRpcConnections();
            return rpcSession;
        } catch (IOException e) {
            log.error("Error closing session for participant with private id {}", participantPrivateId, e);
        }
        return null;
    }

    private Transaction getAndRemoveTransaction(String participantPrivateId, Integer transactionId) {
        RpcConnection rpcSession = rpcConnections.get(participantPrivateId);
        if (rpcSession == null) {
            log.warn("Invalid WebSocket session id {}", participantPrivateId);
            return null;
        }
        log.trace("#{} - {} transactions", participantPrivateId, rpcSession.getTransactions().size());
        Transaction t = rpcSession.getTransaction(transactionId);
        rpcSession.removeTransaction(transactionId);
        return t;
    }

    /*public void showRpcConnections() {
        Iterator<ConcurrentMap.Entry<String, RpcConnection>> entries =
                rpcConnections.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, RpcConnection> entry = entries.next();
            log.info("<{}, {}>",
                    entry.getKey(),entry.getValue().toString());
        }
    }*/

    public void showRpcConnections() {
        log.info("<PRIVATE_ID, RPC_CONNECTION>: {}", this.rpcConnections.toString());
    }

    public RpcConnection getRpcConnection(String participantPrivateId) {
        if (rpcConnections.containsKey(participantPrivateId)) {
            return this.rpcConnections.get(participantPrivateId);
        } else {
            return null;
        }
    }

    public RpcConnection getRpcConnectionByParticipantPublicId(String participantPublicId) {
        ClusterConnection connection = clusterRpcService
                .getConnection(participantPublicId);
        if (clusterRpcService
                .isLocalHostMember(connection.getMemberId())) {
            return rpcConnections.get(connection.getParticipantPrivateId());
        } else {
            return null;
        }
    }
    /*public boolean connectionIsLocalMember(String participantPublicId) {
        if (clusterRpcService.connectionExist(participantPublicId)) {
            ClusterConnection clusterConnection =
                    clusterRpcService.getConnection(participantPublicId);
            if (rpcConnections.containsKey(
                    clusterConnection.getParticipantPrivateId())) {
                return clusterRpcService
                        .isLocalHostMember(
                                rpcConnections.get(clusterConnection.getParticipantPrivateId()).getMemberId());
            } else {
                throw new CloudMediaException(CloudMediaException.Code.TRANSPORT_ERROR_CODE,
                        "participantPublicId : {" + participantPublicId + "} connection not Exist in local and remote member");
            }
        } else {
            throw new CloudMediaException(CloudMediaException.Code.TRANSPORT_ERROR_CODE,
                    "participantPublicId : {" + participantPublicId + "} connection not Exist in local and remote member");
        }
    }*/

    /**
     * @param participantPublicId
     * @param method
     * @param object
     */
    public void sendNotificationByPublicId(String participantPublicId,
                                           String method,
                                           JsonObject object) {
        if (participantPublicId == null) {
            log.error("participantPublicId can not null");
            return;
        }

        if (clusterRpcService
                .getConnection(participantPublicId) != null) {
            ClusterConnection connection =
                    clusterRpcService.getConnection(participantPublicId);
            if (clusterRpcService.isLocalHostMember(connection.getMemberId())) {
                log.info("send {} local host message",participantPublicId);
                sendNotification(
                        connection.getParticipantPrivateId(), method, object);
            } else {
                String message = null;
                if (object != null) {
                    message = object.toString();
                }
                log.info("send {} remote host message member id {}",participantPublicId,connection.getMemberId());
                clusterRpcService.executeToMember(
                        new ClusterRpcNotification(
                                participantPublicId, method, message), connection.getMemberId());
            }
        }
    }

}
