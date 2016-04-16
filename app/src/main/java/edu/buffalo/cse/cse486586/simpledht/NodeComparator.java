package edu.buffalo.cse.cse486586.simpledht;

import java.util.Comparator;

/**
 * Created by tushar on 4/7/16.
 */
public class NodeComparator implements Comparator<NodeDetails> {
    @Override
    public int compare(NodeDetails n1, NodeDetails n2){

        return n1.getNodeIdHash().compareTo(n2.getNodeIdHash());
    }
}
