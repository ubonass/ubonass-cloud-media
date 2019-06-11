package org.ubonass.media.server.cluster;

import lombok.Data;

import java.io.Serializable;

@Data
public class ClusterConnection implements Serializable {

    private static final long serialVersionUID = -275185919285085971L;
    private String memberId;//当前连接位于集群中的那台主机,该ID以uuid进行标识
    private String clientId;//当前客户端的客户唯一标识码
    private String sessionId;//participantPrivateId

    public ClusterConnection(String clientId,
                             String sessionId,
                             String memberId) {
        this.clientId = clientId;
        this.sessionId = sessionId;
        this.memberId = memberId;
    }
}
