package org.opendcs.computations.compgraph;

import decodes.cwms.CwmsGroupHelper;
import decodes.cwms.CwmsTimeSeriesDb;
import decodes.sql.DbKey;
import decodes.tsdb.CTimeSeries;
import decodes.tsdb.CompAppInfo;
import decodes.tsdb.DataCollection;
import decodes.tsdb.TsdbAppTemplate;
import decodes.tsdb.DbComputation;
import decodes.tsdb.DbCompParm;
import decodes.tsdb.DbCompResolver;
import decodes.tsdb.TimeSeriesIdentifier;
import decodes.tsdb.TsGroup;
import decodes.tsdb.VarFlags;
import decodes.util.CmdLineArgs;

import ilex.cmdline.StringToken;
import ilex.cmdline.TokenOptions;
import ilex.var.TimedVariable;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.slf4j.LoggerFactory;

import lrgs.gui.DecodesInterface;

import opendcs.dai.CompDependsDAI;
import opendcs.dai.ComputationDAI;
import opendcs.dai.LoadingAppDAI;
import opendcs.dai.TimeSeriesDAI;
import opendcs.dai.TsGroupDAI;
import opendcs.dao.DaoHelper;




/**
 * Runs a simple query to show the status of the system
 * @author L2EDDMAN
 */
public class BuildCompGraph extends TsdbAppTemplate
{
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(BuildCompGraph.class);
    //private StringToken compname = new StringToken("C","Computation to Run","", TokenOptions.optSwitch, "" );
    private StringToken group = new StringToken("G","Time Series Group","",TokenOptions.optSwitch|TokenOptions.optRequired,"");

    private static final String compquery = "select comp.computation_id,comp.computation_name,comp.algorithm_id,algo.algorithm_name,ALGOPARM.ALGO_ROLE_NAME, COMPPARM.SITE_DATATYPE_ID FROM ccp.cp_computation comp \n" +
"    INNER JOIN ccp.cp_algorithm algo \n" +
"    ON comp.algorithm_id = algo.algorithm_id \n" +
"    INNER JOIN CCP.CP_ALGO_TS_PARM algoparm     \n" +
"    ON algo.algorithm_id = ALGOPARM.ALGORITHM_ID and ALGOPARM.PARM_TYPE='o'\n" +
"    INNER JOIN CCP.CP_COMP_TS_PARM compparm\n" +
"    ON ALGOPARM.ALGO_ROLE_NAME = COMPPARM.ALGO_ROLE_NAME";

    public BuildCompGraph()
    {
        super("compgraph.log");
    }

    @Override
    public void initDecodes()
    {
        //skip this
    }

    @Override
    protected void addCustomArgs( CmdLineArgs cmdLineArgs)
    {
        cmdLineArgs.addToken(group);
        appNameArg.setDefaultValue("compgraph");
    }

    @Override
    protected void runApp() throws Exception
    {
        DecodesInterface.initDecodesMinimal(cmdLineArgs.getProfile().getFile().getAbsolutePath());

        Graph graph = new Graph();
        try(Connection conn = theDb.getConnection();
            ComputationDAI compDAO = theDb.makeComputationDAO();
            TsGroupDAI groupDAO = theDb.makeTsGroupDAO();
            TimeSeriesDAI tsDAO = theDb.makeTimeSeriesDAO();
            CompDependsDAI compDependsDAO = theDb.makeCompDependsDAO();
            LoadingAppDAI loadingAppDAO = theDb.makeLoadingAppDAO();
            DaoHelper dao = new DaoHelper(theDb, compquery, conn);
        )
        {
            TsGroup my_group = groupDAO.getTsGroupByName(group.getValue());
            CwmsGroupHelper helper = new CwmsGroupHelper((CwmsTimeSeriesDb) theDb);
            helper.expandTsGroup(my_group);
            ArrayList<TimeSeriesIdentifier> expandedList = my_group.getExpandedList();
            DataCollection dc = new DataCollection();

            for (TimeSeriesIdentifier tsOut: expandedList)
            {
                CTimeSeries cts = theDb.makeTimeSeries(tsOut);
                // doesn't matter what the value is, we're just tricking the system into processing the data
                // and it needs a value with the DB_ADDED flag set
                TimedVariable tv = new TimedVariable(1);
                tv.setTime( new Date() );
                tv.setFlags( VarFlags.DB_ADDED );
                cts.addSample(tv);
                dc.addTimeSeries(cts);

                // we want ALL of the dependencies in this app
                String q = "select computation_id from ccp.cp_comp_depends where ts_id = ?";
                dao.doQuery(q, rs->
                {
                    cts.addDependentCompId( DbKey.createDbKey(rs, 1) );
                },
                tsOut.getKey());
            }

            DbCompResolver resolver = new DbCompResolver(theDb);
            DbComputation comps[] = resolver.resolve(dc);
            for (DbComputation comp: comps)
            {
                log.info("Gathering inputs and outputs for '{}'", comp.getName());
                Iterator<DbCompParm> parms = comp.getParms();
                String compid="COMP"+comp.getId();
                // do this twice, because we need a unique ID for the comps
                // so combine the inputs
                while( parms !=null && parms.hasNext())
                {
                    DbCompParm parm = parms.next();
                    if (parm.isInput() && !parm.getSiteDataTypeId().isNull())
                    {
                        compid = compid + "_" + parm.getSiteDataTypeId();
                    }
                }
                parms = comp.getParms();
                while (parms !=null && parms.hasNext())
                {
                    DbCompParm parm =parms.next();

                    if (parm.isOutput())
                    {
                        DbKey target = parm.getSiteDataTypeId();
                        info("\tOutput: " + parm.getRoleName() +"/");
                        if (!target.isNull())
                        {
                            String target_id = "TS"+target;
                            TimeSeriesIdentifier ts = tsDAO.getTimeSeriesIdentifier(target);
                            info( ts.getUniqueString() );
                            graph.addNode(new GraphNode(target_id, ts.getUniqueString(),"TS", ts.getPart("param"),null));
                            graph.addNode( new GraphNode( compid, comp.getName(), "COMP","comp", comp ));
                            /*
                            TODO: may need to figure out a way to deal with input vs output properties
                            on edges.

                            */
                            graph.addEdge( new GraphEdge( compid,target_id, "\"extra\": {}" ) );
                        }
                    }
                    else
                    {
                        log.trace("Input: {}", parm.getRoleName());
                        DbKey source = parm.getSiteDataTypeId();
                        if (source.getValue() != DbKey.NullKey.getValue())
                        {
                            TimeSeriesIdentifier tsin = tsDAO.getTimeSeriesIdentifier(source);

                            String source_id = "TS"+source;
                            log.trace("Full Timeseries '{}'", tsin.getUniqueString() );
                            graph.addNode( new GraphNode( compid, comp.getName(), "COMP", "comp",comp));
                            graph.addNode( new GraphNode( source_id,tsin.getUniqueString(),"TS", tsin.getPart("param"),null));

                            // find this edges properties.
                            String rolename = parm.getRoleName();
                            ArrayList<String> proplist = new ArrayList<String>();
                            Properties properties = comp.getProperties();
                            for (Object key: properties.keySet())
                            {
                                String prop = (String)key;
                                if (prop.contains(rolename))
                                {
                                    proplist.add( String.format("\"%s\": \"%s\"",prop,properties.getProperty(prop)) );
                                }
                            }
                            String data = "";
                            if (proplist.size() > 0)
                            {
                                data = "\"extra\": { \"properties\": {" + String.join(",\n",proplist) + "}\n}\n";
                            }
                            else
                            {
                                data = "\"extra\": {}";
                            }
                            // special case for basin precip until names fixed

                            graph.addEdge(new GraphEdge( source_id,compid, data  ) );
                        }
                        else
                        {
                            log.trace(" ");
                        }
                    }
                }
            }
            graph.printGraph();
        }

    }



    public static void main( String args[]){
        BuildCompGraph byapp = new BuildCompGraph();

        System.setProperty("DCSTOOL_HOME", "C:\\OPENDCS\\7.0.12");
        try {
            DecodesInterface.setGUI(false);

            byapp.execute(args);
        } catch (Exception ex) {
            Logger.getLogger(BuildCompGraph.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

}
