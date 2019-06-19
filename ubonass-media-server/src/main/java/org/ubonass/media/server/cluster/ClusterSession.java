package org.ubonass.media.server.cluster;

import lombok.Data;

import java.io.Serializable;

@Data
public class ClusterSession implements Serializable {


    private static final long serialVersionUID = -4833914942506147989L;

    private String sessionName;

    public ClusterSession(String sessionName) {
        this.sessionName = sessionName;
    }

}
