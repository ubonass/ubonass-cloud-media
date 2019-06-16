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

import org.kurento.jsonrpc.Session;
import org.kurento.jsonrpc.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RpcConnection{

    private static final Logger log =
            LoggerFactory.getLogger(RpcConnection.class);

    private Session session;
    private ConcurrentMap<Integer, Transaction> transactions;
    private String sessionId;
    private String participantPrivateId;
    private String memberId;//当前连接位于集群中的那台主机,该ID以uuid进行标识
    private String participantPublicId;//当前客户端的客户唯一标识码

    //add by jeffrey
    public RpcConnection(String participantPublicId, Session session) {
        this.participantPublicId = participantPublicId;
        this.session = session;
        this.transactions = new ConcurrentHashMap<>();
        this.participantPrivateId = session.getSessionId();
    }

    public RpcConnection(String clientId, String memberId, Session session) {
        this.participantPublicId = participantPublicId;
        this.memberId = memberId;
        this.session = session;
        this.transactions = new ConcurrentHashMap<>();
        this.participantPrivateId = session.getSessionId();
    }

    public RpcConnection(Session session) {
        this.session = session;
        this.transactions = new ConcurrentHashMap<>();
        this.participantPrivateId = session.getSessionId();
    }

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public String getParticipantPublicId() {
        return participantPublicId;
    }

    public void setParticipantPublicId(String participantPublicId) {
        this.participantPublicId = participantPublicId;
    }

    public Session getSession() {
        return session;
    }

    public String getParticipantPrivateId() {
        return participantPrivateId;
    }

    public void setParticipantPrivateId(String participantPrivateId) {
        this.participantPrivateId = participantPrivateId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Transaction getTransaction(Integer transactionId) {
        return transactions.get(transactionId);
    }

    public void addTransaction(Integer transactionId, Transaction t) {
        Transaction oldT = transactions.putIfAbsent(transactionId, t);
        if (oldT != null) {
            log.error("Found an existing transaction for the key {}", transactionId);
        }
    }

    public void removeTransaction(Integer transactionId) {
        transactions.remove(transactionId);
    }

    public Collection<Transaction> getTransactions() {
        return transactions.values();
    }

}
