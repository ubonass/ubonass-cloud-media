/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
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

package org.ubonass.media.server.call;

import org.kurento.jsonrpc.Session;
import org.springframework.stereotype.Component;
import org.ubonass.media.server.rpc.RpcConnection;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class UserRpcRegistry {

    private ConcurrentHashMap<String, UserRpcConnection> usersByUserId = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, UserRpcConnection> usersByParticipantPrivateIdId = new ConcurrentHashMap<>();

    public void register(UserRpcConnection user) {
        usersByUserId.put(user.getUserId(), user);
        usersByParticipantPrivateIdId.put(user.getParticipantPrivateId(), user);
    }

    public UserRpcConnection getByUserId(String userId) {
        return usersByUserId.get(userId);
    }

    public UserRpcConnection
    getByUserRpcConnection(RpcConnection rpcConnection) {
        return usersByParticipantPrivateIdId
                .get(rpcConnection.getParticipantPrivateId());
    }

    public boolean exists(String userId) {
        return usersByUserId.keySet().contains(userId);
    }

    public UserRpcConnection
    removeByUserRpcConnection(UserRpcConnection rpcConnection) {
        final UserRpcConnection user = getByUserRpcConnection(rpcConnection);
        if (user != null) {
            usersByUserId.remove(user.getUserId());
            usersByParticipantPrivateIdId.remove(rpcConnection.getParticipantPrivateId());
        }
        return user;
    }

}
