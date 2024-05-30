/*
 *  As a work of the United States Government, this package
 *  is in the public domain within the United States. Additionally,
 *  We waive copyright and related rights in the work worldwide
 *  through the CC0 1.0 Universal Public Domain Dedication
 *  (which can be found at https://creativecommons.org/publicdomain/zero/1.0/).

 */
package org.opendcs.computations.compgraph;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


/**
 *
 * @author Michael Neilson <michael.a.neilson@usace.army.mil>
 */
public class Graph
{
    ArrayList<GraphNode> nodes;
    ArrayList<GraphEdge> edges;


    public Graph()
    {
        nodes = new ArrayList<GraphNode>();
        edges = new ArrayList<GraphEdge>();
    }

    public boolean addNode(GraphNode node)
    {
        for( int i=0; i < nodes.size(); i++)
        {
            GraphNode tmp = nodes.get(i);

            if (tmp.id.equalsIgnoreCase( node.id ))
            {
                return false; // already have
            }
        }
        nodes.add(node); // we don't already have this node
        return true;
    }

    public boolean addEdge(GraphEdge edge)
    {
        for (GraphEdge tmp : edges)
        {
            if (tmp.source.equalsIgnoreCase(edge.source)
                && tmp.target.equalsIgnoreCase(edge.target)
                )
            {
                return false; //we have this edge already
            }
        }
        edges.add(edge);
        return true;
    }

    void printGraph()
    {
        System.out.println("{ \"data\": ");
        System.out.println("{ \"nodes\": [");
        List<String> nodesStr = nodes.stream().map(n -> n.toString()).collect(Collectors.toList());
        System.out.print(String.join(",\n",nodesStr.toArray(new String[0])));
        System.out.println("]");
        System.out.println(",");
        System.out.println(" \"edges\": [");
        List<String> edgeStr = edges.stream().map(n -> n.toString()).collect(Collectors.toList());
        System.out.print(String.join("\n", edgeStr.toArray(new String[0])));
        System.out.println("]");
        ArrayList<String> datatypes = new ArrayList<String>();
        for( GraphNode node: nodes)
        {
            if (!datatypes.contains("\""+node.datatype+"\""))
            {
                datatypes.add("\""+node.datatype +"\"");
            }
        }
        System.out.println("}, \"categories\": [");
        System.out.print(String.join("\n", datatypes));
        System.out.println("]");
        System.out.println("}");
    }
}
