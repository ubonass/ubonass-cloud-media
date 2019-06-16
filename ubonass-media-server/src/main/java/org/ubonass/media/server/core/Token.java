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

package org.ubonass.media.server.core;


import lombok.Data;
import org.ubonass.media.java.client.CloudMediaRole;
import org.ubonass.media.server.coturn.TurnCredentials;
import org.ubonass.media.server.kurento.core.KurentoTokenOptions;

@Data
public class Token {

    private String token;
    private CloudMediaRole role;
    private String serverMetadata = "";
    private TurnCredentials turnCredentials;

    private KurentoTokenOptions kurentoTokenOptions;

    public Token(String token) {
        this.token = token;
    }

    public Token(String token, CloudMediaRole role,
                 String serverMetadata,
                 TurnCredentials turnCredentials,
                 KurentoTokenOptions kurentoTokenOptions) {
        this.token = token;
        this.role = role;
        this.serverMetadata = serverMetadata;
        this.turnCredentials = turnCredentials;
        this.kurentoTokenOptions = kurentoTokenOptions;
    }

    @Override
    public String toString() {
        if (this.role != null)
            return this.role.name();
        else
            return this.token;
    }

}