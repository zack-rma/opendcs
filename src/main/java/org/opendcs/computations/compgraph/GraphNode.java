/*
 *  As a work of the United States Government, this package
 *  is in the public domain within the United States. Additionally,
 *  We waive copyright and related rights in the work worldwide
 *  through the CC0 1.0 Universal Public Domain Dedication
 *  (which can be found at https://creativecommons.org/publicdomain/zero/1.0/).

 */
package org.opendcs.computations.compgraph;

import decodes.tsdb.DbComputation;
import java.util.ArrayList;
import java.util.Properties;

/**
 *
 * @author Michael Neilson <michael.a.neilson@usace.army.mil>
 */
public class GraphNode
{
    public String id;
    public String name;
    public String type;
    public String datatype;
    public DbComputation comp;

    GraphNode(String key, String name,  String type, String datatype, DbComputation comp )
    {
        this.id = key;
        this.name = name;
        this.comp = comp;
        this.type = type;
        this.datatype = datatype.toLowerCase().split("-")[0]; // we only care about the base parameter
    }

    public String toString()
    {
        return String.format("{\n\"data\":{ \"id\": \"%s\", \"name\": \"%s\", \"label\": \"%s\", \"type\": \"%s\", \"datatype\": \"%s\", \"extra\": %s }\n}", this.id, this.name,this.name,this.type,this.datatype,this.extradata());
    }

    public String extradata()
    {
        String data = "{}";
        if (comp != null)
        {
            data = "{\n";
            data = data +"\"algorithmName\": \"" + comp.getAlgorithmName() + "\",\n";
            data = data +"\"loadingApplication\": \"" + comp.getApplicationName() + "\",\n";
            data = data +"\"isGroupComp\": \"" + comp.hasGroupInput() + "\",\n";
            data = data +"\"groupName\": \"" + comp.getGroupName() + "\",\n";
            data = data +"\"processDataFrom\": \"" + comp.getValidStart() + "\",\n";
            data = data +"\"processDataUntil\": \"" + comp.getValidEnd() + "\",\n";
            ArrayList<String> proppairs = new ArrayList<String>();
            Properties properties = comp.getProperties();
            for (Object key: properties.keySet())
            {
                String prop = properties.getProperty((String)key );
                if (prop == null || prop.equals("\"\"") || prop.equals(""))
                {
                    prop = "not defined";
                }
                proppairs.add( String.format("\"%s\": \"%s\"", (String)key, prop ));
            }

            if (properties.size() > 0)
            {
                data = data + "\"properties\": {\n";
                data = data + String.join(",", proppairs);
                data = data + "}\n";
            }
            else
            {
                data = data + "\"properties\": {}\n";
            }
            data = data + "}\n";
        }
        else
        {
            // perhaps to something with the time series data?
        }
        return data;
    }


}
