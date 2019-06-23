/*
 * (C) Copyright 2017-2019 CloudMedia (https://cloudMedia.io/)
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

package org.ubonass.media.server.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.ubonass.media.java.client.CloudMediaRole;

@Data
@Component
public class CloudMediaConfig {

    @Value("${server.port}")
    private String serverPort;

    @Value("${cloudmedia.secret}")
    private String secret;

    @Value("${cloudmedia.publicurl}")
    private String publicUrl; // local, docker, [FINAL_URL]

    @Value("${cloudmedia.recording.enable}")
    private boolean recordingModuleEnable;

    @Value("${cloudmedia.recording.path}")
    private String recordingPath;

    @Value("${cloudmedia.recording.public-access}")
    private boolean recordingPublicAccess;

    @Value("${cloudmedia.recording.notification}")
    private String recordingNotification;

    @Value("${cloudmedia.recording.custom-layout}")
    private String recordingCustomLayout;

    @Value("${cloudmedia.recording.version}")
    private String recordingVersion;

    @Value("${cloudmedia.recording.autostop-timeout}")
    private int recordingAutostopTimeout;

    @Value("${cloudmedia.recording.composed-url}")
    private String recordingComposedUrl;

    @Value("${cloudmedia.streams.video.max-recv-bandwidth}")
    private int videoMaxRecvBandwidth;

    @Value("${cloudmedia.streams.video.min-recv-bandwidth}")
    private int videoMinRecvBandwidth;

    @Value("${cloudmedia.streams.video.max-send-bandwidth}")
    private int videoMaxSendBandwidth;

    @Value("${cloudmedia.streams.video.min-send-bandwidth}")
    private int videoMinSendBandwidth;

    @Value("#{'${spring.profiles.active:}'.length() > 0 ? '${spring.profiles.active:}'.split(',') : \"default\"}")
    private String springProfile;

    /**
     * https:url
     */
    private String finalUrl;

    /**
     * wsUrl
     */
    private String wsUrl;

    public boolean recordingCustomLayoutChanged() {
        return !"/opt/cloudmedia/custom-layout".equals(this.recordingCustomLayout);
    }

    public boolean recordingCustomLayoutChanged(String path) {
        return !"/opt/cloudmedia/custom-layout".equals(path);
    }

    public CloudMediaRole[] getRolesFromRecordingNotification() {
        CloudMediaRole[] roles;
        switch (this.recordingNotification) {
            case "none":
                roles = new CloudMediaRole[0];
                break;
            case "moderator":
                roles = new CloudMediaRole[]{CloudMediaRole.MODERATOR};
                break;
            case "publisher_moderator":
                roles = new CloudMediaRole[]{CloudMediaRole.PUBLISHER, CloudMediaRole.MODERATOR};
                break;
            case "all":
                roles = new CloudMediaRole[]{CloudMediaRole.SUBSCRIBER, CloudMediaRole.PUBLISHER, CloudMediaRole.MODERATOR};
                break;
            default:
                roles = new CloudMediaRole[]{CloudMediaRole.PUBLISHER, CloudMediaRole.MODERATOR};
        }
        return roles;
    }

}

