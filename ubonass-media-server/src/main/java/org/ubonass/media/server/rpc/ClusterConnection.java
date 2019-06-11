package org.ubonass.media.server.rpc;

import lombok.Data;
import org.kurento.jsonrpc.Session;

import javax.annotation.sql.DataSourceDefinition;
import java.io.Serializable;

@Data
public class ClusterConnection implements Serializable {

    private static final long serialVersionUID = -275185919285085971L;
    private String memberId;//当前连接位于集群中的那台主机,该ID以uuid进行标识
    private String clientId;//当前客户端的客户唯一标识码

    public ClusterConnection(String clientId, String memberId) {
        this.clientId = clientId;
        this.memberId = memberId;
    }
}
