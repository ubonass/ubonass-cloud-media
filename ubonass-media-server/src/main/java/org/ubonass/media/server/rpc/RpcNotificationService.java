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

package org.ubonass.media.server.rpc;

import com.google.gson.JsonObject;
import com.hazelcast.core.IMap;
import lombok.Data;
import org.kurento.jsonrpc.Session;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.ubonass.media.client.CloudMediaException;
import org.ubonass.media.server.cluster.ClusterConnection;
import org.ubonass.media.server.cluster.ClusterRpcService;
import org.ubonass.media.server.core.SessionManager;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RpcNotificationService {

    private static final Logger log = LoggerFactory.getLogger(RpcNotificationService.class);

    private ConcurrentMap<String, RpcConnection> rpcConnections = new ConcurrentHashMap<>();

    /**
     * key为用户远程连的客户唯一标识,Value为ClusterConnection,针对所有集群
     */
    private IMap<String, ClusterConnection> clusterConnections;

    @Autowired
    private ClusterRpcService clusterRpcService;

    @PostConstruct
    public void init() {
        this.clusterConnections =
                clusterRpcService.getHazelcastInstance().getMap("clusterConnections");
    }

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

    public ClusterConnection newClusterConnection(RpcConnection rpcConnection) {
        if (rpcConnection == null) return null;
        ClusterConnection connection = new ClusterConnection(
                rpcConnection.getClientId(),
                rpcConnection.getParticipantPrivateId(),
                rpcConnection.getMemberId());
        ClusterConnection oldConnection =
                clusterConnections.putIfAbsent(rpcConnection.getClientId(), connection);
        if (oldConnection != null) {
            log.warn("Concurrent initialization of rpcSession #{}", rpcConnection.getClientId());
            connection = oldConnection;
        }
        return connection;
    }

    /**
     * 返回null表示成功
     *
     * @param rpcConnection
     * @return
     */
    public ClusterConnection addClusterConnection(RpcConnection rpcConnection) {
        if (rpcConnection == null) return null;
        ClusterConnection connection = new ClusterConnection(
                rpcConnection.getClientId(),
                rpcConnection.getParticipantPrivateId(),
                rpcConnection.getMemberId());
        ClusterConnection oldConnection =
                clusterConnections.putIfAbsent(rpcConnection.getClientId(), connection);
        return oldConnection;
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

    public ClusterConnection closeClusterConnection(String clientId) {
        if (!clusterConnections.containsKey(clientId)) return null;
        ClusterConnection clusterConnection = clusterConnections.remove(clientId);
        if (clusterConnection == null) {
            log.error("No session found for private id {}, unable to cleanup", clientId);
            return null;
        }
        return clusterConnection;
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

    public RpcConnection getRpcConnectionByClientId(String clientId) {
        if (connectionIsLocalMember(clientId)) {
            return rpcConnections.get(clusterConnections.get(clientId).getSessionId());
        } else {
            return null;
        }
    }

    public ClusterConnection getClusterConnection(String clientId) {
        if (clusterConnections.containsKey(clientId)) {
            return this.clusterConnections.get(clientId);
        } else {
            return null;
        }
    }


    public boolean connectionExist(String clientId) {
        return clusterConnections.containsKey(clientId);
    }

    public boolean connectionIsLocalMember(String clientId) {
        if (connectionExist(clientId)) {
            ClusterConnection clusterConnection =
                    clusterConnections.get(clientId);
            if (rpcConnections.containsKey(
                    clusterConnection.getSessionId())) {
                return clusterRpcService
                        .isLocalHostMember(
                                rpcConnections.get(clusterConnection.getSessionId()).getMemberId());
            } else {
                return false;
            }
        } else {
            throw new CloudMediaException(CloudMediaException.Code.TRANSPORT_ERROR_CODE,
                    "clientId : {" + clientId + "} connection not Exist in local and remote member");
        }
    }

    /**
     * @param clientId
     * @param method
     * @param object
     */
    public void sendNotificationByClientId(String clientId,
                                           String method,
                                           JsonObject object) {
        if (clientId == null) {
            log.error("clientId can not null");
            return;
        }
        if (connectionIsLocalMember(clientId)) {
            sendNotification(
                    clusterConnections.get(clientId).getSessionId(), method, object);
        } else {
            if (clusterConnections.containsKey(clientId)) {
                clusterRpcService.executeToMember(
                        new RpcNotificationRunnable(
                                clientId, method, object),
                        clusterConnections.get(clientId).getMemberId());
            }
        }
    }

    @Data
    private class RpcNotificationRunnable implements Runnable, Serializable {

        private static final long serialVersionUID = -3246285197224927455L;

        private String clientId;
        private String method;
        private JsonObject object;

        public RpcNotificationRunnable(
                String clientId,
                String method,
                JsonObject object) {
            this.clientId = clientId;
            this.method = method;
            this.object = object;
        }

        @Override
        public void run() {
            if (clientId == null || method == null) return;
            RpcConnection rpcConnection =
                    getRpcConnection(getClusterConnection(clientId).getSessionId());
            if (rpcConnection == null) return;
            try {
                if (object != null) {
                    rpcConnection.getSession().sendNotification(method, object);
                } else {
                    rpcConnection.getSession().sendNotification(method);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}
