package edu.buffalo.cse.cse486586.simpledht;

import java.io.Serializable;

/**
 * Created by tushar on 4/7/16.
 */
public class NodeDetails implements Serializable {
    private String port;
    private String predecessorPort;
    private String successorPort;
    private String nodeIdHash;
    private String predecessorNodeIdHash;
    private String successorNodeIdHash;
    private boolean firstNode;

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getPredecessorPort() {
        return predecessorPort;
    }

    public void setPredecessorPort(String predecessorPort) {
        this.predecessorPort = predecessorPort;
    }

    public String getSuccessorPort() {
        return successorPort;
    }

    public void setSuccessorPort(String successorPort) {
        this.successorPort = successorPort;
    }

    public String getPredecessorNodeIdHash() {
        return predecessorNodeIdHash;
    }

    public void setPredecessorNodeIdHash(String predecessorNodeIdHash) {
        this.predecessorNodeIdHash = predecessorNodeIdHash;
    }

    public String getSuccessorNodeIdHash() {
        return successorNodeIdHash;
    }

    public void setSuccessorNodeIdHash(String successorNodeIdHash) {
        this.successorNodeIdHash = successorNodeIdHash;
    }

    public String getNodeIdHash() {
        return nodeIdHash;
    }

    public void setNodeIdHash(String nodeIdHash) {
        this.nodeIdHash = nodeIdHash;
    }

    public boolean isFirstNode() {
        return firstNode;
    }

    public void setFirstNode(boolean firstNode) {
        this.firstNode = firstNode;
    }

    @Override
    public String toString() {
        return "NodeDetails{" +
                "port='" + port + '\'' +
                ", isFirstNode='" + firstNode + '\'' +
                ", predecessor='" + predecessorNodeIdHash + '\'' +
                ", myHash='" + nodeIdHash + '\'' +
                ", successor='" + successorNodeIdHash + '\'' +
                '}';
    }
}
