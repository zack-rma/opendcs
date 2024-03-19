package opendcs.opentsdb;

import ilex.util.TextUtil;
import ilex.var.NoConversionException;
import ilex.var.TimedVariable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;

import decodes.cwms.CwmsFlags;
import decodes.cwms.CwmsTsId;
import decodes.db.Constants;
import decodes.db.DataPresentation;
import decodes.db.DataType;
import decodes.db.Database;
import decodes.db.EngineeringUnit;
import decodes.db.IntervalList;
import decodes.db.PresentationGroup;
import decodes.db.Site;
import decodes.db.UnitConverter;
import decodes.sql.DbKey;
import decodes.tsdb.BadTimeSeriesException;
import decodes.tsdb.CTimeSeries;
import decodes.tsdb.CpDependsNotify;
import decodes.tsdb.DataCollection;
import decodes.tsdb.DbIoException;
import decodes.tsdb.NoSuchObjectException;
import decodes.tsdb.RecordRangeHandle;
import decodes.tsdb.TasklistRec;
import decodes.util.DecodesSettings;
import decodes.util.TSUtil;
import decodes.tsdb.TimeSeriesIdentifier;
import decodes.tsdb.VarFlags;
import opendcs.dai.AlarmDAI;
import opendcs.dai.CompDependsDAI;
import opendcs.dai.CompDependsNotifyDAI;
import opendcs.dai.DataTypeDAI;
import opendcs.dai.SiteDAI;
import opendcs.dai.TimeSeriesDAI;
import opendcs.dao.CompDependsNotifyDAO;
import opendcs.dao.DaoBase;
import opendcs.dao.DatabaseConnectionOwner;
import opendcs.dao.DbObjectCache;
import decodes.tsdb.TimeSeriesDb;

public class OpenTimeSeriesDAO extends DaoBase implements TimeSeriesDAI
{
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(OpenTimeSeriesDAO.class);
    // Open TSDB Uses CWMS 6-part Time Series Identifiers
    protected final static DbObjectCache<TimeSeriesIdentifier> cache =
        new DbObjectCache<TimeSeriesIdentifier>(2 * 60 * 60 * 1000L, false); // 2 hr cache
    protected Calendar utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    public NumberFormat suffixFmt = NumberFormat.getIntegerInstance();
    private static final String ts_columns = "sample_time, ts_value, flags, source_id";
    private long lastCacheReload = 0L;
    private String appModule = null;

    /** data sources are immutable in the database so no need to refresh them. */
    private static HashMap<String, TsDataSource> key2ds = new HashMap<String, TsDataSource>();

    private long lastTsidCacheRead = 0L;

    public static final long MSEC_PER_UTC_DAY = 24 * 3600 * 1000L;


    public static final String ts_spec_columns =
        "TS_ID, SITE_ID, DATATYPE_ID, STATISTICS_CODE, INTERVAL_ID, " +
        "DURATION_ID, TS_VERSION, ACTIVE_FLAG, STORAGE_UNITS, " +
        "STORAGE_TABLE, STORAGE_TYPE, " +
        "MODIFY_TIME, DESCRIPTION, UTC_OFFSET, ALLOW_DST_OFFSET_VARIATION, " +
        "OFFSET_ERROR_ACTION";

    public OpenTimeSeriesDAO(DatabaseConnectionOwner tsdb)
    {
        super(tsdb, "OpenTimeSeriesDAO");
        suffixFmt.setMinimumIntegerDigits(4);
        suffixFmt.setGroupingUsed(false);
    }

    public TimeSeriesIdentifier getTimeSeriesIdentifier(String uniqueString) throws DbIoException, NoSuchObjectException
    {
        int paren = uniqueString.lastIndexOf('(');
        String displayName = null;
        if (paren > 0 && uniqueString.trim().endsWith(")"))
        {
            displayName = uniqueString.substring(paren+1);
            uniqueString = uniqueString.substring(0,  paren);
            int endParen = displayName.indexOf(')');
            if (endParen > 0)
            {
                displayName = displayName.substring(0,  endParen);
            }
        }

        CwmsTsId tsid = (CwmsTsId)cache.getByUniqueName(uniqueString);
        if (tsid != null)
        {
            if (displayName != null)
            {
                tsid.setDisplayName(displayName);
            }
            return tsid;
        }

        tsid = new CwmsTsId();
        tsid.setUniqueString(uniqueString);
        DbKey siteId = DbKey.NullKey;
        try (SiteDAI siteDAO = db.makeSiteDAO())
        {
            siteId = siteDAO.lookupSiteID(tsid.getSiteName());
        }
        if (siteId.isNull())
        {
            throw new NoSuchObjectException("No such site '" + tsid.getSiteName() + "'");
        }
        DbKey dataTypeId = tsid.getDataTypeId();
        if (dataTypeId == null || dataTypeId.isNull())
        {
            throw new NoSuchObjectException("No such data type for '" + uniqueString + "'");
        }

        Interval interval = IntervalList.instance().getByName(tsid.getInterval());
        if (interval == null)
        {
            throw new NoSuchObjectException("No such interval '" + tsid.getInterval() + "'");
        }
        Interval duration = IntervalList.instance().getByName(tsid.getDuration());
        if (duration == null)
        {
            throw new NoSuchObjectException("No such duration '" + tsid.getDuration() + "'");
        }

        String q = "select ts_id from ts_spec where "
            + "SITE_ID = ?"
            + " and DATATYPE_ID = ?"
            + " and lower(STATISTICS_CODE) = lower(?)"
            + " and INTERVAL_ID = ?"
            + " and DURATION_ID = ?"
            + " and lower(TS_VERSION) = lower(?)";

        try
        {
            TimeSeriesIdentifier ret = getSingleResultOr(q, rs ->
                {
                    try
                    {
                        return getTimeSeriesIdentifier(DbKey.createDbKey(rs, 1));
                    }
                    catch (Exception ex)
                    {
                        throw new SQLException("Error retrieving TimeSeriesIdentifier", ex);
                    }
                },
                null,
                siteId, dataTypeId, tsid.getStatisticsCode(),
                interval.getKey(), duration.getKey(), tsid.getVersion()
            );
            if (ret != null)
            {
                if (displayName != null)
                {
                    ret.setDisplayName(displayName);
                }
                return ret;
            }
            else
            {
                throw new NoSuchObjectException("No Time Series matching '" + uniqueString + "'");
            }
        }
        catch (SQLException ex)
        {
            throw new DbIoException("Unable to get Time Series Identifier", ex);
        }
    }

    @Override
    public TimeSeriesIdentifier getTimeSeriesIdentifier(DbKey key)
        throws DbIoException, NoSuchObjectException
    {
        TimeSeriesIdentifier ret = cache.getByKey(key);
        if (ret != null)
        {
            log.trace("getTimeSeriesIdentifier(ts_code={}) id='{}' from cache.", key, ret.getUniqueString());
            return ret;
        }
        else
        {
            log.trace("getTimeSeriesIdentifier(ts_code={}) Not in cache.", key);
        }

        return readTSID(key);
    }

    /**
     * Read from the Database
     * @param key
     * @return
     * @throws DbIoException
     * @throws NoSuchObjectException
     */
    private CwmsTsId readTSID(DbKey key)
        throws DbIoException, NoSuchObjectException
    {
        String q = "SELECT " + ts_spec_columns
                 + " from TS_SPEC "
                 + " where ts_id = ?";
        try
        {
            CwmsTsId ret = getSingleResultOr(q, rs ->
                {
                    try
                    {
                        return rs2TsId(rs, true);
                    }
                    catch (DbIoException | NoSuchObjectException ex)
                    {
                        throw new SQLException("Unable to create tsid instance.", ex);
                    }
                },
                null,
                key);
            if (ret != null)
            {
                long now = System.currentTimeMillis();
                ret.setReadTime(now);
                cache.put(ret);
                return ret;
            }
            else
            {
                throw new NoSuchObjectException("No time-series with ts_code=" + key);
            }
        }
        catch(Exception ex)
        {
            throw new DbIoException("Error looking up TS Info for TS_CODE=" + key, ex);
        }

    }

    /**
     * Given a result set with columns specified by ts_spec_columns,
     * parse a complete CwmsTsId.
     * @param rs The result set
     * @param createDataType true if we can create the data type record (CWMS only)
     * @return the CwmsTsId
     * @throws SQLException
     * @throws DbIoException
     * @throws NoSuchObjectException
     */
    private CwmsTsId rs2TsId(ResultSet rs, boolean createDataType)
        throws SQLException, DbIoException, NoSuchObjectException
    {
        DbKey tsKey = DbKey.createDbKey(rs, 1);
        DbKey siteId = DbKey.createDbKey(rs, 2);
        DbKey dataTypeId = DbKey.createDbKey(rs, 3);
        String statCode = rs.getString(4);
        DbKey intervalId = DbKey.createDbKey(rs, 5);
        DbKey durationId = DbKey.createDbKey(rs, 6);
        String version = rs.getString(7);
        boolean active = TextUtil.str2boolean(rs.getString(8));
        String storageUnitsAbbr = rs.getString(9);
        int storageTable = rs.getInt(10);
        String storageType = rs.getString(11);
        Date lastModified = db.getFullDate(rs, 12);
        String description = rs.getString(13);

        int x = rs.getInt(14);
        Integer utcOffset = rs.wasNull() || x == -1 ? null : x;

        boolean allowDstOffsetVariation = TextUtil.str2boolean(rs.getString(15));
        String s = rs.getString(16);
        OffsetErrorAction offsetErrorAction = null;
        if (s != null && !rs.wasNull())
        {
            try
            {
                offsetErrorAction = OffsetErrorAction.valueOf(s.toUpperCase());
            }
            catch(Exception ex)
            {
                log.warn("Invalid value for offsetErrorAction '{}'", s);
                offsetErrorAction = null;
            }
        }

        Site site = null;
        try (SiteDAI siteDAO = db.makeSiteDAO())
        {
            site = siteDAO.getSiteById(siteId);
        }
        DataType dataType = DataType.getDataType(dataTypeId);
        if (dataType == null)
        {
            throw new NoSuchObjectException("Invalid DataType ID = " + dataTypeId);
        }
        Interval interval = IntervalList.instance().getById(intervalId);
        if (interval == null)
        {
            throw new NoSuchObjectException("Invalid interval ID = " + intervalId);
        }
        Interval duration = IntervalList.instance().getById(durationId);
        if (duration == null)
        {
            throw new NoSuchObjectException("Invalid duration ID = " + durationId);
        }

        String path = site.getPreferredName().getNameValue() + "."
            + dataType.getCode() + "."
            + statCode + "."
            + interval.getName() + "."
            + duration.getName() + "."
            + version;

        CwmsTsId ret = new CwmsTsId(tsKey, path, dataType,
            description, false, utcOffset, storageUnitsAbbr);

        ret.setSite(site);
        ret.setSiteDisplayName(site.getPublicName());
        ret.setDescription(description);
        ret.setActive(active);
        ret.setLastModified(lastModified);
        ret.setStorageTable(storageTable);
        ret.setStorageType(
            storageType == null || storageType.length()==0 ? 'N' :
                storageType.toUpperCase().charAt(0));
        ret.setAllowDstOffsetVariation(allowDstOffsetVariation);
        ret.setOffsetErrorAction(offsetErrorAction);

        return ret;
    }


    @Override
    public void close()
    {
        super.close();
    }

    @Override
    public void fillTimeSeriesMetadata(CTimeSeries ts) throws DbIoException,
        BadTimeSeriesException
    {
        log.trace("fillTimeSeriesMetadata for '{}'", ts.getBriefDescription());
        try
        {
            DbKey ts_code = ts.getSDI();
            CwmsTsId tsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
            if (tsid == null)
            {
                tsid = (CwmsTsId)getTimeSeriesIdentifier(ts_code);
                ts.setTimeSeriesIdentifier(tsid);
            }
            ts.setInterval(tsid.getInterval());
            ts.setBriefDescription(tsid.getDataType().getCode() + " @ "
                + tsid.getSiteName());
            // Set table selector to ParamType.Duration.Version so it will match
            ts.setTableSelector(tsid.getParamType() + "." +
                tsid.getDuration() + "." + tsid.getVersion());

            String existingUnits = ts.getUnitsAbbr();
            if (existingUnits == null || existingUnits.isEmpty() || existingUnits.equalsIgnoreCase("unknown"))
            {
                ts.setUnitsAbbr(tsid.getStorageUnits());
            }
        }
        catch(NoSuchObjectException ex)
        {
            log.warn("Error expanding SDI.", ex);
            ts.setDisplayName("unknownSite:unknownType-unknownIntv");
            ts.setUnitsAbbr("EU??");
        }
    }

    @Override
    public int fillTimeSeries( CTimeSeries ts, Date from, Date until )
        throws DbIoException, BadTimeSeriesException
    {
        return fillTimeSeries(ts, from, until, true, true, true);
    }


    @Override
    public int fillTimeSeries(CTimeSeries ts, Date from, Date until,
                              boolean include_lower, boolean include_upper,
                              boolean overwriteExisting)
        throws DbIoException, BadTimeSeriesException
    {
        CwmsTsId ctsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
        if (ctsid == null)
        {
            fillTimeSeriesMetadata(ts);
            ctsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
        }

        if (ctsid == null)
        {
            throw new BadTimeSeriesException("Could not retrieve timeseries meta data. TimeSeriesIdentifier is not present.");
        }

        final UnitConverter unitConverter = getUnitConverter(ts);

        String tableName = makeDataTableName(ctsid);
        List<Object> parameters = new ArrayList<>();
        StringBuilder q = new StringBuilder();
        q.append("select " + ts_columns + " from " + tableName
            + " where ts_id = ?");
        parameters.add(ctsid.getKey());
        if (from != null)
        {
            q.append(" and sample_time " + (include_lower ? " >= " : " > ") + " ?");
            parameters.add(from);
        }
        if (until != null)
        {
            q.append(" and sample_time " + (include_upper ? " <= " : " < ") + " ?");
            parameters.add(until);
        }

        try
        {
            final CwmsTsId tsId = ctsid;
            final int n[] = new int[1];
            n[0] = 0;
            doQuery(q.toString(), rs ->
                {
                    TimedVariable tv = rs2tv(rs, tsId, unitConverter);

                    // For computation processor, we never want to overwrite data
                    // we already have. For a report generator, we DO.
                    Date d = tv.getTime();
                    if (!overwriteExisting && ts.findWithin(d.getTime() / 1000L, 10) != null)
                    {
                        return;
                    }

                    ts.addSample(tv);
                    n[0]++;
                },
                parameters.toArray(new Object[0]));
            return n[0];
        }
        catch (SQLException ex)
        {
            String msg = "Error getting data for time series="
                    + ctsid.getUniqueName();
            throw new DbIoException(msg ,ex);
        }
    }

    @Override
    public int fillTimeSeries(CTimeSeries ts, Collection<Date> queryTimes)
        throws DbIoException, BadTimeSeriesException
    {
        if (queryTimes.size() == 0)
        {
            return 0;
        }

        CwmsTsId ctsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
        if (ctsid == null)
        {
            fillTimeSeriesMetadata(ts);
            ctsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
        }

        final UnitConverter unitConverter = getUnitConverter(ts);

        String tableName = makeDataTableName(ctsid);

        int MAX_IN_CLAUSE = 200;
        String baseQ = "select " + ts_columns + " from " + tableName
            + " where ts_id = " + ctsid.getKey() + " and sample_time in (";

        StringBuilder qb = new StringBuilder(baseQ);
        int numFilled[] = new int[1];
        numFilled[0] = 0;
        int numThisQuery = 0;
        for(Iterator<Date> dit = queryTimes.iterator(); dit.hasNext();)
        {
            final Date d = dit.next();
            qb.append(d.getTime());
            if (!dit.hasNext() || ++numThisQuery >= MAX_IN_CLAUSE)
            {
                qb.append(")");
                try
                {
                    final CwmsTsId tsId = ctsid;
                    doQuery(qb.toString(), rs ->
                    {
                        TimedVariable tv = rs2tv(rs, tsId, unitConverter);

                        Date tvDate = tv.getTime();
                        if (ts.findWithin(tvDate.getTime() / 1000L, 10) != null)
                        {
                            return;
                        }
                        ts.addSample(tv);
                        numFilled[0]++;
                    });
                }
                catch (SQLException ex)
                {
                    String msg = "Error getting data for time series="+ ctsid.getUniqueName();
                    throw new DbIoException(msg, ex);
                }

                numThisQuery = 0;
                qb = new StringBuilder(baseQ);
            }
            else
            {
                qb.append(",");
            }
        }
        return numFilled[0];
    }

    @Override
    public TimedVariable getPreviousValue(CTimeSeries ts, Date refTime) throws DbIoException, BadTimeSeriesException
    {
        CwmsTsId ctsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
        if (ctsid == null)
        {
            fillTimeSeriesMetadata(ts);
            ctsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
        }

        final UnitConverter unitConverter = getUnitConverter(ts);

        String tableName = makeDataTableName(ctsid);
        String q = "select " + ts_columns + " from " + tableName
            + " where ts_id = ?"
            + " and sample_time = "
            + "(select max(sample_time) from " + tableName
            + " where ts_id = ?"
            + " and sample_time < ?";

        try
        {
            final CwmsTsId tsId = ctsid;
            return getSingleResultOr(q, rs ->
                {
                    TimedVariable tv = rs2tv(rs, tsId, unitConverter);
                    if (ts.findWithin(tv.getTime(), 10) == null)
                    {
                        ts.addSample(tv);
                    }
                    return tv;
                },
                null,
                ctsid.getKey(), ctsid.getKey(), refTime
            );
        }
        catch (SQLException ex)
        {
            String msg = String.format("getPreviousValue() Error in query '%s'", q);
            throw new DbIoException(msg, ex);
        }
    }

    private UnitConverter getUnitConverter(CTimeSeries ts)
    {
        final CwmsTsId ctsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
        UnitConverter unitConverter = null;
        if (ctsid.getStorageType() == OpenTsdb.TABLE_TYPE_NUMERIC
         && ts.getUnitsAbbr() != null
         && !ts.getUnitsAbbr().equalsIgnoreCase(ctsid.getStorageUnits()))
        {
            unitConverter = Database.getDb().unitConverterSet.get(
                                EngineeringUnit.getEngineeringUnit(ctsid.getStorageUnits()),
                                EngineeringUnit.getEngineeringUnit(ts.getUnitsAbbr()));
        }

        return unitConverter;
    }

    @Override
    public TimedVariable getNextValue(CTimeSeries ts, Date refTime)
        throws DbIoException, BadTimeSeriesException
    {
        CwmsTsId ctsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
        if (ctsid == null)
        {
            fillTimeSeriesMetadata(ts);
            ctsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
        }

        final UnitConverter unitConverter = getUnitConverter(ts);

        String tableName = makeDataTableName(ctsid);
        String q = "select " + ts_columns + " from " + tableName
            + " where ts_id = ?"
            + " and sample_time = "
            + "(select min(sample_time) from " + tableName
            + " where ts_id = ?"
            + " and sample_time > ?";

        try
        {
            final CwmsTsId tsId = ctsid;
            return getSingleResultOr(q, rs ->
                {
                    TimedVariable tv = rs2tv(rs, tsId, unitConverter);
                    if (ts.findWithin(tv.getTime(), 10) == null)
                    {
                        ts.addSample(tv);
                    }
                    return tv;
                },
                null,
                ctsid.getKey(), ctsid.getKey(), refTime
            );
        }
        catch (SQLException ex)
        {
            String msg = String.format("getPreviousValue() Error in query '%s'", q);
            throw new DbIoException(msg, ex);
        }
    }

    @Override
    public void saveTimeSeries(CTimeSeries ts) throws DbIoException,
        BadTimeSeriesException
    {
        TimeSeriesIdentifier tsid = ts.getTimeSeriesIdentifier();
        if (tsid == null)
        {
            throw new BadTimeSeriesException("Cannot save time series without TSID.");
        }

        log.trace("Saving '{}', from cp units='{}', required='{}'",
                  tsid.getUniqueString(), ts.getUnitsAbbr(), tsid.getStorageUnits());

        CwmsTsId ctsid = (CwmsTsId)tsid;
        // If we don't already have the timeseries key, either find it, or make the time series.
        if (ctsid.getKey().isNull())
        {
            try
            {
                final TimeSeriesIdentifier tmp = this.getTimeSeriesIdentifier(tsid.getUniqueString());
                ctsid.setKey(tmp.getKey());
            }
            catch (NoSuchObjectException ex)
            {
                log.info("Creating Timeseries");
                try
                {
                    final DbKey tmp = this.createTimeSeries(ctsid);
                    ctsid.setKey(tmp);
                }
                catch (NoSuchObjectException ex2)
                {
                    throw new BadTimeSeriesException("Unable to create timeseries.", ex2);
                }
            }
        }
        TSUtil.convertUnits(ts, tsid.getStorageUnits());
        log.trace("After TSUtil.convertUnits, cts units={}", ts.getUnitsAbbr());

        String tableName = makeDataTableName(ctsid);

        // First rectify the times in the time series. Depending on the
        // settings we may need to ignore some or adjust the times.
        for(int i=0; i<ts.size(); i++)
        {
            TimedVariable tv = ts.sampleAt(i);
            if ((VarFlags.mustWrite(tv) || VarFlags.mustDelete(tv)) // marked for modification
             && !checkSampleTime(tv, (CwmsTsId)tsid))               // settings say to ignore.
            {
                tv.setFlags(tv.getFlags() & ~(VarFlags.TO_DELETE|VarFlags.TO_WRITE));
            }
        }

        int numNew = 0;
        int numUpdated = 0;
        int numDeleted = 0;
        int numProtected = 0;
        int numNoOverwrite = 0;
        int numErrors = 0;

        // Get list of times that are marked for write or delete.
        ArrayList<Date> times = new ArrayList<Date>();
        for (int i = 0; i < ts.size(); i++)
        {
            TimedVariable tv = ts.sampleAt(i);
            if (VarFlags.mustWrite(tv) || VarFlags.mustDelete(tv))
                times.add(tv.getTime());
        }
        if (times.size() == 0)
        {
            log.trace(" No times marked for save or delete.");
            return;
        }

        // Determine if samples already exist at these time stamps.
        CTimeSeries alreadyInDb =
            new CTimeSeries(ts.getSDI(), ts.getInterval(), ts.getTableSelector());
        alreadyInDb.setTimeSeriesIdentifier(ctsid);
        fillTimeSeries(alreadyInDb, times);
        alreadyInDb.sort();

        DbKey daoSourceId = getTsDataSource().getSourceId();
        if (daoSourceId == null)
        {
            String msg = "Cannot determine data source ID.";
            throw new BadTimeSeriesException(msg);
        }
        Date now = new Date();

        // Go through samples in the time series I am supposed to write.
        // For each flagged sample, see if there is already one in the database.
        // Difference between CWMS and OpenTSDB: OpenTSDB stores flags exactly
        // as they are defined in CwmsFlags.java. CWMS shifts them around on
        // read and write.
        for (int idx = 0; idx < ts.size(); idx++)
        {
            TimedVariable tv2write = ts.sampleAt(idx);
            if (!(VarFlags.mustWrite(tv2write) || VarFlags.mustDelete(tv2write)))
            {
                continue;
            }

            DbKey tvSourceId = tv2write.getSourceId();
            if (DbKey.isNull(tvSourceId))
            {
                tvSourceId = daoSourceId;
            }

            TimedVariable dbTv = alreadyInDb.findWithin(tv2write.getTime(), 5);

            String q = "";
            try
            {
                if (dbTv == null)
                {
                    if (VarFlags.mustWrite(tv2write))
                    {
                        // New value!
                        int flags = tv2write.getFlags()
                            & ~(CwmsFlags.RESERVED_4_VAR | CwmsFlags.RESERVED_4_COMP);
                        q = "insert into "
                            + tableName + "(TS_ID, SAMPLE_TIME, TS_VALUE, FLAGS, SOURCE_ID, DATA_ENTRY_TIME) "
                            + " values("
                            + tsid.getKey()
                            + ", " + db.sqlDate(tv2write.getTime())
                            + ", " + (ctsid.getStorageType() == 'N' ? tv2write.getDoubleValue()
                                      : sqlString(tv2write.getStringValue()))
                            + ", " + flags
                            + ", " + tvSourceId
                            + ", " + db.sqlDate(now) + ")";
                        doModify(q);
                        numNew++;
                    }
                    // else if mustDelete do nothing. There is no DB value.
                }
                // If existing value is protected, we may not change it!
                else if ((dbTv.getFlags() & CwmsFlags.PROTECTED) != 0)
                {
                    log.warn("DB Value for '{}' at time {} is protected. Cannot modify!",
                             tsid.getUniqueString(), db.getLogDateFormat().format(dbTv.getTime()));
                    numProtected++;
                    continue;
                }
                else if (!VarFlags.isNoOverwrite(tv2write))
                    // There is a db value and it is unprotected
                    // Also, the NO_OVERWRITE bit means only write if it's new.
                {
                    int flags = tv2write.getFlags()
                        & ~(CwmsFlags.RESERVED_4_VAR | CwmsFlags.RESERVED_4_COMP);
                    if (VarFlags.mustWrite(tv2write))
                    {
                        q = "update " + tableName + " set  ts_value = "
                            + (ctsid.getStorageType() == 'N' ? tv2write.getDoubleValue()
                                : sqlString(tv2write.getStringValue()))
                            + ", flags = " + flags
                            + ", source_id = " + tvSourceId
                            + ", data_entry_time = " + db.sqlDate(now)
                            + " where ts_id = " + tsid.getKey()
                            + " and sample_time = " + db.sqlDate(dbTv.getTime());
                        doModify(q);
                        numUpdated++;
                    }
                    else if (VarFlags.mustDelete(tv2write))
                    {
                        q = "delete from " + tableName
                            + " where ts_id = " + tsid.getKey()
                            + " and sample_time = " + db.sqlDate(dbTv.getTime());
                        doModify(q);
                        numDeleted++;
                    }
                }
                else
                    numNoOverwrite++;
            }
            catch(NoConversionException ex)
            {
                log.atWarn()
                   .setCause(ex)
                   .log("Cannot convert {} to number to store in database: ", tv2write);
            }
            catch(DbIoException ex)
            {
                log.atWarn()
                   .setCause(ex)
                   .log("Error in query '{}'", q);
                numErrors++;
            }
        }

        log.trace("saveTimeSeries: New={}, updated={}, deleted={} protected={}, noOverwrite={}, errors={}",
                  numNew, numUpdated, numDeleted, numProtected, numNoOverwrite, numErrors);
    }

    @Override
    public void deleteTimeSeriesRange(CTimeSeries ts, Date from, Date until) throws DbIoException, BadTimeSeriesException
    {
        try
        {
            this.inTransaction(dao ->
            {
                try (AlarmDAI alarmDAO = db.makeAlarmDAO())
                {
                    alarmDAO.deleteCurrentAlarm(ts.getTimeSeriesIdentifier().getKey(), null);
                    alarmDAO.deleteHistoryAlarms(ts.getTimeSeriesIdentifier().getKey(), from, until);
                }

                CwmsTsId ctsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
                if (ctsid == null)
                {
                    fillTimeSeriesMetadata(ts);
                    ctsid = (CwmsTsId)ts.getTimeSeriesIdentifier();
                }

                final String tableName = makeDataTableName(ctsid);
                ArrayList<Object> parameters = new ArrayList<>();
                String q = "delete from " + tableName
                    + " where ts_id = ?"
                    + " and flags & ? = 0 ";
                parameters.add(ctsid.getKey());
                parameters.add(CwmsFlags.PROTECTED);
                if (from != null)
                {
                    q = q + " and sample_time >= ?";
                    parameters.add(from);
                }
                if (until != null)
                {
                    q = q + " and sample_time  <= ?";
                    parameters.add(until);
                }
                doModify(q, parameters.toArray(new Object[0]));
            });
        }
        catch (Exception ex)
        {
            if (ex.getCause() instanceof BadTimeSeriesException)
            {
                throw (BadTimeSeriesException)ex.getCause();
            }
            else if(ex.getCause() instanceof DbIoException)
            {
                throw (DbIoException)ex.getCause();
            }
            else
            {
                throw new DbIoException("Unable to delete timeseries data range.", ex);
            }
        }
    }

    @Override
    public void deleteTimeSeries(TimeSeriesIdentifier tsid) throws DbIoException
    {
        final CwmsTsId ctsid = (CwmsTsId)tsid;
        try (AlarmDAI alarmDAO = db.makeAlarmDAO();
             CompDependsDAI compDependsDAO = db.makeCompDependsDAO();)
        {
            this.inTransaction(dao ->
            {
                alarmDAO.inTransactionOf(dao);
                compDependsDAO.inTransactionOf(dao);
                try
                {
                    alarmDAO.deleteCurrentAlarm(tsid.getKey(), null);
                    alarmDAO.deleteHistoryAlarms(tsid.getKey(), null, null);
                }
                catch(Exception ex)
                {
                    throw new Exception("error deleting alarm records.", ex);
                }

                try
                {
                    String tableName = makeDataTableName(ctsid);
                    String q = "delete from " + tableName
                        + " where ts_id = ?"
                        + " and flags & ? = 0 ";
                    dao.doModify(q, ctsid.getKey(), CwmsFlags.PROTECTED);
                    dao.doModify("delete from ts_property where ts_id = ?", ctsid.getKey());
                    compDependsDAO.deleteCompDependsForTsKey(ctsid.getKey());
                }
                catch(Exception ex)
                {
                    throw new Exception("deleteTimeSeries error deleting computation dependencies.", ex);
                }

                try
                {
                    dao.doModify("delete from cp_comp_tasklist where ts_id = ?", ctsid.getKey());
                }
                catch (Exception ex)
                {
                    throw new Exception("Error removing tasklist entries", ex);
                }

                try
                {
                    dao.doModify("delete from ts_spec where ts_id = ?", ctsid.getKey());
                }
                catch (Exception ex)
                {
                    throw new Exception("Error deleting ts_spec entry", ex);
                }

                try
                {
                    String q = "select num_ts_present, est_annual_values from storage_table_list "
                    + "where table_num = ? and storage_type=?";
                    int num_andEstimate[] =
                                getSingleResult(q,
                                                rs -> new int[]{ rs.getInt(1), rs.getInt(2)},
                                                ctsid.getStorageTable(), ctsid.getStorageType());
                    num_andEstimate[0]--;
                    num_andEstimate[1] -= interval2estAnnualValues(ctsid.getIntervalOb());
                    q = "update storage_table_list set num_ts_present = ?"
                        + ", est_annual_values = ?"
                        + " where table_num = ?";
                    doModify(q, num_andEstimate[0], num_andEstimate[1], ctsid.getStorageTable());
                }
                catch (Exception ex)
                {
                    throw new Exception("Unable to modify storage table", ex);
                }

                try(CompDependsNotifyDAI dai = db.makeCompDependsNotifyDAO())
                {
                    CpDependsNotify cdn = new CpDependsNotify();
                    cdn.setEventType(CpDependsNotify.TS_DELETED);
                    cdn.setKey(ctsid.getKey());
                    dai.saveRecord(cdn);
                }
                catch (Exception ex)
                {
                    throw new Exception("Unable to create comp depends notification.", ex);
                }
            });
        }
        catch (Exception ex)
        {
            throw new DbIoException(String.format("Error deleting time series. %s",tsid.toString()), ex);
        }
    }

    @Override
    public CTimeSeries makeTimeSeries(TimeSeriesIdentifier tsid)
        throws DbIoException, NoSuchObjectException
    {
        CTimeSeries ret = new CTimeSeries(tsid.getKey(), tsid.getInterval(), tsid.getTableSelector());
        try
        {
            fillTimeSeriesMetadata(ret);
        }
        catch(BadTimeSeriesException ex)
        {
            throw new NoSuchObjectException("Unable to fill in timeseries metadata.", ex);
        }
        return ret;
    }

    /**
     * Check the sample time against constraints dictated by the interval,
     * database settings, and time series settings. If settings say to
     * round the time, the sample time may be adjusted.
     * This is called before writing a sample to the database.
     * @param sample the sample to check
     * @param tsid the time series ID.
     * @return true if sample OK to save, false if sample should be discarded.
     */
    private boolean checkSampleTime(TimedVariable sample, CwmsTsId tsid) throws DbIoException
    {
        Interval intv = tsid.getIntervalOb();

        if (intv == null || intv.getCalMultiplier() == 0)
        {
            return true; // Irregular, so no checking
        }

        OffsetErrorAction offsetErrorAction = tsid.getOffsetErrorAction();
        if (offsetErrorAction == null)
        {
            offsetErrorAction = OpenTsdbSettings.instance().offsetErrorActionEnum;
        }

        // If (ignore) then just return true. Don't bother with checking.
        if (offsetErrorAction == OffsetErrorAction.IGNORE)
        {
            return true;
        }

        // Compute a UTC Offset as follows:
        // - UTC Offset must always be less than the interval.
        // - It represents # of seconds to top of previous even UTC interval.
        // - Example interval=5minutes, time=00:18:05, then offset := 185 (3m 5s)

        utcCal.setTime(sample.getTime());
        utcCal.set(Calendar.SECOND, 0);
        switch(intv.getCalConstant())
        {
            case Calendar.MINUTE:
                utcCal.set(Calendar.MINUTE,
                    (utcCal.get(Calendar.MINUTE) / intv.getCalMultiplier()) * intv.getCalMultiplier());
                break;
            case Calendar.HOUR_OF_DAY:    // truncate to top of (hour*mult)
                utcCal.set(Calendar.MINUTE, 0);
                utcCal.set(Calendar.HOUR_OF_DAY,
                    (utcCal.get(Calendar.HOUR_OF_DAY) / intv.getCalMultiplier()) * intv.getCalMultiplier());
                break;
            case Calendar.DAY_OF_MONTH: // truncate to top of (day*mult)
                utcCal.set(Calendar.HOUR_OF_DAY, 0);
                utcCal.set(Calendar.MINUTE, 0);
                // Now truncate back, using number of days since epoch
                utcCal.setTimeInMillis(
                    (daysSinceEpoch(utcCal.getTimeInMillis()) / intv.getCalMultiplier())
                        * intv.getCalMultiplier() * MSEC_PER_UTC_DAY);
                break;
            case Calendar.WEEK_OF_YEAR:
                utcCal.set(Calendar.HOUR_OF_DAY, 0);
                utcCal.set(Calendar.MINUTE, 0);
                utcCal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
                utcCal.set(Calendar.WEEK_OF_YEAR,
                    (utcCal.get(Calendar.WEEK_OF_YEAR) / intv.getCalMultiplier()) * intv.getCalMultiplier());
                break;
            case Calendar.MONTH:
                utcCal.set(Calendar.HOUR_OF_DAY, 0);
                utcCal.set(Calendar.MINUTE, 0);
                utcCal.set(Calendar.DAY_OF_MONTH, 1);
                utcCal.set(Calendar.MONTH,
                    (utcCal.get(Calendar.MONTH) / intv.getCalMultiplier()) * intv.getCalMultiplier());
                break;
            case Calendar.YEAR:
                utcCal.set(Calendar.HOUR_OF_DAY, 0);
                utcCal.set(Calendar.MINUTE, 0);
                utcCal.set(Calendar.DAY_OF_MONTH, 1);
                utcCal.set(Calendar.MONTH, Calendar.JANUARY);
                utcCal.set(Calendar.YEAR,
                    (utcCal.get(Calendar.YEAR) / intv.getCalMultiplier()) * intv.getCalMultiplier());
                break;
        }

        // Compute offset in seconds. Will always be positive because we truncate utcCal backward.
        int offset = (int)((sample.getTime().getTime() - utcCal.getTimeInMillis()) / 1000L);

        // if (utcOffset unassigned) update ts_spec and write offset to data
        // then return true.
        if (tsid.getUtcOffset() == null)
        {
            log.debug("Time series '{}' setting new UTC Offset = '{}'", tsid.getUniqueString(), offset);
            String q = "update ts_spec set utc_offset = ?";
            try
            {
                doModify(q, offset);
            }
            catch (SQLException ex)
            {
                throw new DbIoException("Unable to set utc_offset", ex);
            }
            tsid.setUtcOffset(offset);
            return true;
        }
        else
        {
            log.debug("Time series '{}' already has offset = '{}', new computed offset='{}'",
                      tsid.getUniqueString(), tsid.getUtcOffset(), offset);
        }
        // Else check against the stored utc offset in seconds.
        int offsetError = offset - tsid.getUtcOffset();
        boolean violation = (offsetError != 0);

        if (!violation)
        {
            return true; // UTC Offsets are exactly equal!
        }


        if (intv.getCalConstant() == Calendar.MINUTE
         || (intv.getCalConstant() == Calendar.HOUR_OF_DAY && intv.getCalMultiplier() == 1))
        {
            violation = true;
        }
        else
        {
            boolean allowDstVariation = tsid.isAllowDstOffsetVariation();

            if (allowDstVariation &&
                ((intv.getCalConstant() == Calendar.HOUR_OF_DAY && intv.getCalMultiplier() > 1)
              || intv.getCalConstant() == Calendar.DAY_OF_MONTH
              || intv.getCalConstant() == Calendar.WEEK_OF_YEAR))
            {
                if (offsetError == -3600 || offsetError == 3600)
                {
                    violation = false;
                }
            }
            else if (intv.getCalConstant() == Calendar.MONTH)
            {
                // In monthly value, offset is seconds since start of month.
                // It may span a DST change.
                if (allowDstVariation &&
                    (offsetError == -3600 || offsetError == 3600))
                {
                    violation = false;
                }

                //TODO Consider use case where end of month is stored.
                // In march offset is 30d (31-1). In feb this is 27.
                // What if stored offset is 30d but this is 27d?
                // What if stored offset is 27d but this is 30d?
                // So (I think) offsetError can be +/- 1d, 2d, or 3d.
                // Also if allowDstVariation, it may also be +/- 1h.
            }
            else if (intv.getCalConstant() == Calendar.YEAR)
            {
                // max offsetError is 1day * (mult/4) + 1 (i.e. as much as 1 day for every 4 years)
                // DST variation may apply because rules occasionally change as to
                // when DST starts/stops in the year. So if allowDstVariation, it may also be +/- 1h.
                for(int x = 1; x <= intv.getCalMultiplier()/4 + 1; x++)
                {
                    if (offsetError == 3600*24
                     || (allowDstVariation && offsetError == 3600*24 + 3600)
                     || (allowDstVariation && offsetError == 3600*24 - 3600)
                     || offsetError == -3600*24
                     || (allowDstVariation && offsetError == -3600*24 + 3600)
                     || (allowDstVariation && offsetError == -3600*24 - 3600))
                    {
                        violation = false;
                        break;
                    }
                }
            }
        }
        if (violation)
        {
            final StringBuilder msg = new StringBuilder(
                  "Offset violation in time series '{}' stored offset={}, computed offset={} at time {}");
            List<Object> logParameters = new ArrayList<>();
            logParameters.add(tsid.getUniqueName());
            logParameters.add(tsid.getUtcOffset());
            logParameters.add(offset);
            logParameters.add(db.getLogDateFormat().format(sample.getTime()));
            if (offsetErrorAction == OffsetErrorAction.ROUND)
            {
                // utcCal stores the time before the sample time.
                // offset already is # seconds between this and actual sample time.
                Date before = utcCal.getTime();
                utcCal.add(intv.getCalConstant(), intv.getCalMultiplier());
                Date after = utcCal.getTime();
                int afterOffset = (int)
                    ((utcCal.getTimeInMillis() - sample.getTime().getTime()) / 1000L);
                sample.setTime(offset < afterOffset ? before : after);
                violation = false;
                msg.append(" Rounded to {}");
                logParameters.add(db.getLogDateFormat().format(sample.getTime()));
                log.warn(msg.toString(), logParameters.toArray(new Object[0]));
            }
            else if (offsetErrorAction == OffsetErrorAction.REJECT)
            {
                msg.append(" -- REJECTING.");
                log.warn(msg.toString(), logParameters.toArray(new Object[0]));
                return false;
            }
        }
        return !violation;
    }

    private int daysSinceEpoch(long msecTime)
    {
        return (int)(msecTime /= MSEC_PER_UTC_DAY);
    }

    public String makeDataTableName(CwmsTsId tsid) throws DbIoException
    {
        int tableNum = tsid.getStorageTable();
        if (tableNum == -1)
        {
            tableNum = allocateTable(tsid);
        }

        return (tsid.getStorageType() == 'N'
                    ? "TS_NUM_"
                    : "TS_STRING_")
              + suffixFmt.format(tableNum);
    }

    /**
     * Allocates a storage table for the passed TSID. Updates the storage table
     * stats and the ts_spec for this TSID.
     * @param tsid
     * @return
     * @throws DbIoException
     */
    private int allocateTable(CwmsTsId tsid) throws DbIoException
    {
        String q = "select * from storage_table_list "
            + "where storage_type = '" + tsid.getStorageType() + "' "
            + "and est_annual_values = "
            + "(select min(est_annual_values) from storage_table_list where storage_type = '"
            + tsid.getStorageType() + "') order by table_num";
        ResultSet rs = doQuery(q);
        try
        {
            if (rs != null && rs.next())
            {
                int tableNum = rs.getInt(1);
                int numTsPresent = rs.getInt(3);
                int estAnnualValues = rs.getInt(4);
                numTsPresent++;
                Interval intv = tsid.getIntervalOb();
                estAnnualValues += interval2estAnnualValues(intv);
                q = "update storage_table_list set num_ts_present = " + numTsPresent
                    + ", est_annual_values = " + estAnnualValues
                    + " where storage_type = '" + tsid.getStorageType() + "' "
                    + " and table_num = " + tableNum;
                doModify(q);
                tsid.setStorageTable(tableNum);

                q = "update ts_spec set storage_table = " + tableNum
                    + " where ts_id = " + tsid.getKey();
                doModify(q);

                return tableNum;
            }
            else
            {
                throw new DbIoException("No storage tables available!");
            }
        }
        catch (SQLException ex)
        {
            throw new DbIoException("Unable to allocate storage table", ex);
        }
    }

    public static int interval2estAnnualValues(Interval intv)
    {
        return
            intv.getCalMultiplier() == 0 ? (365*24) :
            intv.getCalConstant() == Calendar.YEAR ? 1 :
            intv.getCalConstant() == Calendar.MONTH ? 12/intv.getCalMultiplier() :
            intv.getCalConstant() == Calendar.WEEK_OF_YEAR ? 52/intv.getCalMultiplier() :
            intv.getCalConstant() == Calendar.DAY_OF_MONTH ? 365/intv.getCalMultiplier() :
            intv.getCalConstant() == Calendar.HOUR_OF_DAY ? (365*24)/intv.getCalMultiplier() :
            intv.getCalConstant() == Calendar.MINUTE ? (365*24*60)/intv.getCalMultiplier() :
            1;
    }

    private TimedVariable rs2tv(ResultSet rs, CwmsTsId ctsid, UnitConverter unitConverter)
        throws SQLException
    {
        // sample_time, ts_value, flags, source_id
        Date timeStamp = db.getFullDate(rs, 1);
        TimedVariable tv = null;
        if (ctsid.getStorageType() == 'N')
        {
            double d = rs.getDouble(2);
            if (unitConverter != null)
            {
                try
                {
                    d = unitConverter.convert(d);
                }
                catch(Exception ex)
                {
                    log.atWarn()
                       .setCause(ex)
                       .log("Cannot convert value for '{}' with unit converter from {} to {}",
                            ctsid.getUniqueName(), unitConverter.getToAbbr(), unitConverter.getToAbbr());
                }
            }
            tv = new TimedVariable(d);
        }
        else
        {
            tv = new TimedVariable(rs.getString(2));
        }
        tv.setTime(timeStamp);
        tv.setFlags((int)(rs.getLong(3) & 0xffffffffL));
        tv.setSourceId(DbKey.createDbKey(rs, 4));
        return tv;
    }

    @Override
    public ArrayList<TimeSeriesIdentifier> listTimeSeries()
        throws DbIoException
    {
        // MJM 20161025 don't reload more if already done within threshold.
        if (System.currentTimeMillis() - lastCacheReload > cacheReloadMS)
        {
            reloadTsIdCache();
        }
        ArrayList<TimeSeriesIdentifier> ret = new ArrayList<TimeSeriesIdentifier>();
        for (Iterator<TimeSeriesIdentifier> tsidit = cache.iterator(); tsidit.hasNext(); )
        {
            ret.add(tsidit.next());
        }
        return ret;
    }

    @Override
    public ArrayList<TimeSeriesIdentifier> listTimeSeries(boolean forceRefresh)
        throws DbIoException
    {
        if (forceRefresh)
        {
            lastCacheReload = 0L;
        }
        return listTimeSeries();
    }

    @Override
    public synchronized void reloadTsIdCache()
        throws DbIoException
    {
        cache.clear();
        String q = "SELECT " + ts_spec_columns + " from TS_SPEC";

        try
        {
            ResultSet rs = doQuery(q);
            while (rs != null && rs.next())
            {
                try
                {
                    cache.put(rs2TsId(rs, true));
                }
                catch(NoSuchObjectException ex)
                {
                    log.atWarn()
                       .setCause(ex)
                       .log("Cannot create tsid for key={} -- skipped", rs.getLong(1));
                }
            }
        }
        catch(Exception ex)
        {
            throw new DbIoException("Error reading TS_SPEC table.", ex);
        }
        lastCacheReload = System.currentTimeMillis();
    }

    @Override
    public DbObjectCache<TimeSeriesIdentifier> getCache()
    {
        return cache;
    }

    @Override
    public DbKey createTimeSeries(TimeSeriesIdentifier tsid)
        throws DbIoException, NoSuchObjectException, BadTimeSeriesException
    {
        tsid.checkValid();
        CwmsTsId ctsid = (CwmsTsId)tsid;
        DbKey siteId = Constants.undefinedId;
        Site site = tsid.getSite();
        if (site != null)
        {
            siteId = site.getId();
        }
        else
        {
            try (SiteDAI siteDAO = db.makeSiteDAO())
            {
                siteId = siteDAO.lookupSiteID(tsid.getSiteName());
                if (siteId.isNull())
                {
                    throw new NoSuchObjectException("No such site for tsid '" + tsid.getUniqueString() + "'");
                }
                tsid.setSite(siteDAO.getSiteById(siteId));
            }
        }
        DataType dataType = ctsid.getDataType();
        if (DbKey.isNull(dataType.getId()))
        {

            try (DataTypeDAI dtDAO = db.makeDataTypeDAO();)
            {
                dtDAO.writeDataType(dataType); // write will set the id in the object
            }
        }
        Interval interval = IntervalList.instance().getByName(ctsid.getInterval());
        if (interval == null)
        {
            throw new NoSuchObjectException("Invalid interval in tsid '" + tsid.getUniqueString() + "'");
        }
        DbKey intervalId = interval.getKey();
        Interval duration = IntervalList.instance().getByName(ctsid.getDuration());
        if (duration == null)
        {
            throw new NoSuchObjectException("Invalid duration in tsid '" + tsid.getUniqueString() + "'");
        }
        DbKey durationId = duration.getKey();
        String storageUnits = ctsid.getStorageUnits();
        if (storageUnits == null)
        {
            decodes.db.Database db = decodes.db.Database.getDb();
            String pgName = OpenTsdbSettings.instance().storagePresentationGroup;
            PresentationGroup dbpg = db.presentationGroupList.find(pgName);
            if (dbpg == null)
            {
                throw new DbIoException("No such storagePresentationGroup '"
                    + pgName + "'");
            }
            DataPresentation dp = dbpg.findDataPresentation(dataType);
            if (dp == null)
            {
                int idx = dataType.getCode().indexOf('-');
                String baseCode = dataType.getCode().substring(0,idx);
                if (idx != -1)
                {
                    dp = dbpg.findDataPresentation(
                        DataType.getDataType(dataType.getStandard(),
                            baseCode));
                }
                if (dp == null)
                {
                    throw new NoSuchObjectException("No entry in presentation group '"
                        + pgName + "' for datatype " + dataType + ", or its subtype.");
                }
            }
            storageUnits = dp.getUnitsAbbr();
            ctsid.setStorageUnits(storageUnits);
        }


        ctsid.setKey(getKey("TS_SPEC"));
        String q = "insert into TS_SPEC(" + ts_spec_columns + ") values ("
            + ctsid.getKey() + ", "
            + siteId + ", "
            + dataType.getId() + ", "
            + sqlString(ctsid.getStatisticsCode()) + ", "
            + intervalId + ", "
            + durationId + ", "
            + sqlString(ctsid.getVersion()) + ", "
            + sqlBoolean(ctsid.isActive()) + ", "
            + sqlString(storageUnits) + ", "
            + "-1, " // storage table to be allocated on first write
            + "'" + ctsid.getStorageType() + "', "
            + db.sqlDate(new Date()) + ", "
            + sqlString(ctsid.getDescription()) + ", "
            + "null, " // UTC_OFFSET set on first write
            + sqlBoolean(ctsid.isAllowDstOffsetVariation()) + ", "
            + sqlString(ctsid.getOffsetErrorAction().toString())
            + ")";
        doModify(q);

        try (CompDependsNotifyDAI dai = db.makeCompDependsNotifyDAO())
        {
            CpDependsNotify cdn = new CpDependsNotify();
            cdn.setKey(ctsid.getKey());
            cdn.setEventType(CpDependsNotify.TS_CREATED);
            dai.saveRecord(cdn);
        }

        return ctsid.getKey();
    }

    @Override
    public void setAppModule(String module)
    {
        this.appModule = module;
    }

    /**
     * Returns the TsDataSource for this application.
     * @return the TsDataSource object or null if it doesn't exist and can't be created.
     * @throws DbIoException on SQL Error
     */
    public TsDataSource getTsDataSource() throws DbIoException

    {
        if (DbKey.isNull(db.getAppId()))
        {
            log.error("getTsDataSource() Cannot retrieve data source record when appId is null.");
            return null;
        }

        String key = db.getAppId().toString();
        if (appModule != null)
        {
            key = key + "-" + appModule;
        }
        TsDataSource ret = key2ds.get(key);
        if (ret == null)
        {
            String q = "select SOURCE_ID from TSDB_DATA_SOURCE where LOADING_APPLICATION_ID = "
                + db.getAppId()
                + " and MODULE " + (appModule == null ? "IS NULL" : ("= " + sqlString(appModule)));
            ResultSet rs = doQuery2(q);
            try
            {
                if (rs != null && rs.next())
                {
                    DbKey sourceId = DbKey.createDbKey(rs, 1);
                    ret = new TsDataSource(sourceId, db.getAppId(), appModule);
                    key2ds.put(key, ret);
                }
                else
                {
                    DbKey sourceId = this.getKey("TSDB_DATA_SOURCE");
                    ret = new TsDataSource(sourceId, db.getAppId(), appModule);
                    q = "insert into TSDB_DATA_SOURCE(SOURCE_ID, LOADING_APPLICATION_ID, MODULE) "
                        + " values(" + sourceId + ", " + db.getAppId() + ", " + sqlString(appModule) + ")";
                    doModify(q);
                    key2ds.put(key, ret);
                }
            }
            catch(SQLException ex)
            {
                throw new DbIoException("Exception in query '" + q + "': " + ex);
            }
        }
        return ret;
    }

    /**
     * Return a list of all data sources defined in the database.
     * @return
     */
    public ArrayList<TsDataSource> listDataSources()
        throws DbIoException
    {
        ArrayList<TsDataSource> ret = new ArrayList<TsDataSource>();

        String q = "select a.SOURCE_ID, a.LOADING_APPLICATION_ID, a.MODULE, "
            + "b.LOADING_APPLICATION_NAME "
            + "from TSDB_DATA_SOURCE a, HDB_LOADING_APPLICATION b "
            + "where a.LOADING_APPLICATION_ID = b.LOADING_APPLICATION_ID";

        try
        {
            ResultSet rs = doQuery(q);
            while(rs != null && rs.next())
            {
                TsDataSource tds = new TsDataSource(DbKey.createDbKey(rs, 1),
                    DbKey.createDbKey(rs, 2), rs.getString(3));
                tds.setAppName(rs.getString(4));
                ret.add(tds);
            }
        }
        catch (SQLException ex)
        {
            throw new DbIoException("Error listing TSDB_DATA_SOURCE", ex);
        }

        return ret;
    }

    public ArrayList<StorageTableSpec> getTableSpecs(char storageType)
        throws DbIoException
    {
        ArrayList<StorageTableSpec> ret = new ArrayList<StorageTableSpec>();
        String q = "select table_num, num_ts_present, est_annual_values "
            + "from storage_table_list where storage_type = " + sqlString("" + storageType)
            + " order by table_num";
        ResultSet rs = doQuery(q);
        try
        {
            while(rs.next())
            {
                StorageTableSpec spec = new StorageTableSpec(storageType);
                spec.setTableNum(rs.getInt(1));
                spec.setNumTsPresent(rs.getInt(2));
                spec.setEstAnnualValues(rs.getInt(3));
                ret.add(spec);
            }
        }
        catch(SQLException ex)
        {
            throw new DbIoException("Exception in query '" + q + "'", ex);
        }

        return ret;
    }

    @Override
    public void modifyTSID(TimeSeriesIdentifier tsid)
            throws DbIoException, NoSuchObjectException, BadTimeSeriesException
    {
        if (!(tsid instanceof CwmsTsId))
        {
            throw new BadTimeSeriesException("OpenTSDB uses CWMS TSIDs");
        }
        CwmsTsId ctsid = (CwmsTsId)tsid;

        if (DbKey.isNull(tsid.getKey()))
        {
            throw new NoSuchObjectException("Cannot modify TSID with no key!");
        }
        if (tsid.getSite() == null)
        {
            throw new BadTimeSeriesException("Cannot save TSID without Site!");
        }
        if (DbKey.isNull(tsid.getDataTypeId()))
        {
            throw new BadTimeSeriesException("Cannot save TSID without Data Type!");
        }
        if (ctsid.getParamType() == null || ctsid.getParamType().trim().length() == 0)
        {
            throw new BadTimeSeriesException("Cannot save TSID without Statistics Code!");
        }
        if (ctsid.getIntervalOb() == null)
        {
            throw new BadTimeSeriesException("Cannot save TSID without Interval!");
        }
        if (ctsid.getDurationOb() == null)
        {
            throw new BadTimeSeriesException("Cannot save TSID without Duration!");
        }
        if (ctsid.getVersion() == null || ctsid.getVersion().trim().length() == 0)
        {
            throw new BadTimeSeriesException("Cannot save TSID without Version!");
        }


        StringBuilder q = new StringBuilder("update ts_spec set ");
        Map<String,Object> fields = new LinkedHashMap<>();

        // Read the existing tsid with this key
        CwmsTsId existing = this.readTSID(tsid.getKey());

        // Compare each field of the passed tsid with the one in the db
        // add a set clause to the update statement and increment 'n'.
        if (!tsid.getSite().getKey().equals(existing.getSite().getKey()))
        {
            fields.put("site_id",tsid.getSite().getId());
        }
        if (!tsid.getDataTypeId().equals(existing.getDataTypeId()))
        {
            fields.put("datatype_id", tsid.getDataTypeId());
        }
        if (!ctsid.getParamType().equals(existing.getParamType()))
        {
            fields.put("statistics_code",ctsid.getParamType());
        }
        if (!ctsid.getIntervalOb().getKey().equals(existing.getIntervalOb().getKey()))
        {
            fields.put("interval_id", ctsid.getIntervalOb().getKey());
        }
        if (!ctsid.getDurationOb().getKey().equals(existing.getDurationOb().getKey()))
        {
            fields.put("duration_id", ctsid.getDurationOb().getKey());
        }
        if (!ctsid.getVersion().equals(existing.getVersion()))
        {
            fields.put("ts_version", ctsid.getVersion());
        }
        if (ctsid.isActive() != existing.isActive())
        {
            fields.put("activeFlag", ctsid.isActive());
        }
        if (!ctsid.getStorageUnits().equals(existing.getStorageUnits()))
        {
            fields.put("storage_units", ctsid.getStorageUnits());
        }
        if (ctsid.getStorageTable() != existing.getStorageTable())
        {
            fields.put("storage_table", ctsid.getStorageTable());
        }
        if (!TextUtil.strEqualNE(ctsid.getDescription(), existing.getDescription()))
        {
            String desc = ctsid.getDescription();
            if (desc != null && desc.trim().length() == 0)
            {
                desc = null;
            }
            fields.put("description", desc);
        }
        if (!TextUtil.intEqual(ctsid.getUtcOffset(),existing.getUtcOffset()))
        {
            fields.put("utc_offset", ctsid.getUtcOffset());
        }
        if (ctsid.isAllowDstOffsetVariation() != existing.isAllowDstOffsetVariation())
        {
            fields.put("allow_dst_offset_variation", ctsid.isAllowDstOffsetVariation());
        }
        if (ctsid.getOffsetErrorAction() != existing.getOffsetErrorAction())
        {
            fields.put("offset_error_action", ctsid.getOffsetErrorAction().toString());
        }

        if (fields.isEmpty())
        {
            return; // Nothing has changed.
        }
        fields.put("modify_time", System.currentTimeMillis());

        Iterator<String> columnSet = fields.keySet().iterator();
        while(columnSet.hasNext())
        {
            q.append(columnSet.next()).append("=?");
            if(columnSet.hasNext())
            {
                q.append(",");
            }
            q.append(" ");
        }
        q.append(" where ts_id = ?");
        List<Object> parameters =
                     fields.entrySet()
                           .stream()
                           .map(e -> e.getValue())
                           .collect(Collectors.toList());
        parameters.add(tsid.getKey());
        try
        {
            inTransaction(dao ->
            {

                dao.doModify(q.toString(), parameters.toArray(new Object[0]));
                try (CompDependsNotifyDAO dai = (CompDependsNotifyDAO)db.makeCompDependsNotifyDAO())
                {
                    dai.inTransactionOf(dao);
                    CpDependsNotify cdn = new CpDependsNotify();
                    cdn.setKey(ctsid.getKey());
                    cdn.setEventType(CpDependsNotify.TS_MODIFIED);
                    dai.saveRecord(cdn);

                    // Now update the cache.
                    cache.remove(tsid.getKey());
                    cache.put(ctsid);
                }
            });
        }
        catch (Exception ex)
        {
            throw new DbIoException("Unable to modify timeseries definition.", ex);
        }

    }

    /*
     * TSDB version 5 & above use a join with CP_COMP_DEPENDS to determine
     * not only what the new data is, but what computations depend on it.
     * The dependent computation IDs are stored inside each CTimeSeries.
     */


    @Override
    public DataCollection getNewData(DbKey applicationId) throws DbIoException
    {
        // Reload the TSID cache every hour.
        if (System.currentTimeMillis() - lastTsidCacheRead > 3600000L)
        {
            lastTsidCacheRead = System.currentTimeMillis();
            reloadTsIdCache();
        }

        DataCollection dataCollection = new DataCollection();

        // MJM 2/14/18 - From Dave Portin. Original failTimeClause was:
        //        " and (a.FAIL_TIME is null OR "
        //        + "SYSDATE - to_date("
        //        + "to_char(a.FAIL_TIME,'dd-mon-yyyy hh24:mi:ss'),"
        //        + "'dd-mon-yyyy hh24:mi:ss') >= 1/24)";

        int minRecNum = -1;
        String what = "Preparing min statement query";
        String failTimeClause = "";
        if (DecodesSettings.instance().retryFailedComputations)
        {
            failTimeClause = " and (a.FAIL_TIME is null OR "
                + System.currentTimeMillis() + " -  a.FAIL_TIME >= 3600000)";
        }
        String getMinStmtQuery = "select min(a.record_num) from cp_comp_tasklist a "
                + "where a.LOADING_APPLICATION_ID = " + applicationId
                + failTimeClause;

        // 2nd query gets tasklist recs within record_num range.
        String getTaskListStmtQuery =
            "select a.RECORD_NUM, a.TS_ID, a.num_value, a.sample_time, "
            + "a.DELETE_FLAG, a.flags "
            + "from CP_COMP_TASKLIST a "
            + "where a.LOADING_APPLICATION_ID = " + applicationId
            + failTimeClause;
        try
        {
            if (db.isOracle())
            {
                // ROWNUM needs to be part of where clause before ORDER BY clause
                getTaskListStmtQuery = getTaskListStmtQuery + " and ROWNUM < 20000"
                    + " order by a.ts_id, a.sample_time";
            }
            else // PostgreSQL
            {
                // LIMIT goes after the ORDER BY clause.
                getTaskListStmtQuery = getTaskListStmtQuery
                    + " order by a.ts_id, a.sample_time"
                    + " limit 20000";
            }

            ResultSet rs = doQuery(getMinStmtQuery);

            if (rs == null || !rs.next())
            {
                log.debug("No new data for appId={}", applicationId);
                ((TimeSeriesDb)db).reclaimTasklistSpace(this);
                return dataCollection;
            }

            minRecNum = rs.getInt(1);
            if (rs.wasNull())
            {
                log.debug("No new data for appId={}", applicationId);
                minRecNum = -1;
                ((TimeSeriesDb)db).reclaimTasklistSpace(this);
                return dataCollection;
            }
            log.trace("minRecNum={}", minRecNum);
        }
        catch(SQLException ex)
        {
            log.atWarn()
               .setCause(ex)
               .log("getNewData error while {}", what);
            return dataCollection;
        }

        ArrayList<TasklistRec> tasklistRecs = new ArrayList<TasklistRec>();
        ArrayList<Integer> badRecs = new ArrayList<Integer>();
        try
        {
            what = "Executing '" + getTaskListStmtQuery + "'";
            log.trace(what);
            ResultSet rs = doQuery(getTaskListStmtQuery);
            while (rs.next())
            {
                // Extract the info needed from the result set row.
                int recordNum = rs.getInt(1);
                DbKey sdi = DbKey.createDbKey(rs, 2);
                double value = rs.getDouble(3);
                boolean valueWasNull = rs.wasNull();
                Date timeStamp = new Date(rs.getLong(4));
                String df = rs.getString(5);
                boolean deleted = TextUtil.str2boolean(df);
                int flags = rs.getInt(6);

                TasklistRec rec = new TasklistRec(recordNum, sdi, value,
                    valueWasNull, timeStamp, deleted, null, null, flags);
                tasklistRecs.add(rec);
            }

            RecordRangeHandle rrhandle = new RecordRangeHandle(applicationId);

            // Process the real-time records collected above.
            for(TasklistRec rec : tasklistRecs)
            {
                processTasklistEntry(rec, dataCollection, rrhandle, badRecs, applicationId);
            }

            dataCollection.setTasklistHandle(rrhandle);

            // Delete the bad tasklist recs, 250 at a time.
            if (badRecs.size() > 0)
            {
                log.debug("getNewDataSince deleting {} bad tasklist records.", badRecs.size());
            }
            while (badRecs.size() > 0)
            {
                StringBuilder inList = new StringBuilder();
                int n = badRecs.size();
                int x=0;
                for(; x<250 && x<n; x++)
                {
                    if (x > 0)
                        inList.append(", ");
                    inList.append(badRecs.get(x).toString());
                }
                String q = "delete from CP_COMP_TASKLIST "
                    + "where RECORD_NUM IN (" + inList.toString() + ")";
                doModify(q);
                for(int i=0; i<x; i++)
                {
                    badRecs.remove(0);
                }
            }

            // Show each tasklist entry in the log if we're at debug level 3
            if (log.isTraceEnabled())
            {
                List<CTimeSeries> allts = dataCollection.getAllTimeSeries();
                log.trace("getNewData, returning {} TimeSeries.", allts.size());
                for(CTimeSeries ts : allts)
                {
                    log.trace("ts '{}' had {} values.", ts.getTimeSeriesIdentifier().getUniqueString(), ts.size());
                }
            }

            return dataCollection;
        }
        catch(SQLException ex)
        {
            throw new DbIoException("Error while " + what, ex);
        }
    }


    private void processTasklistEntry(TasklistRec rec, DataCollection dataCollection, RecordRangeHandle rrhandle,
                                      ArrayList<Integer> badRecs, DbKey applicationId)
        throws DbIoException
    {
        // Find time series if already in data collection.
        // If not construct one and add it.
        CTimeSeries cts = dataCollection.getTimeSeriesByUniqueSdi(rec.getSdi());
        if (cts == null)
        {
            try
            {
                TimeSeriesIdentifier tsid = getTimeSeriesIdentifier(rec.getSdi());
                String tabsel = tsid.getPart("paramtype") + "." +
                    tsid.getPart("duration") + "." + tsid.getPart("version");
                cts = new CTimeSeries(rec.getSdi(), tsid.getInterval(),
                    tabsel);
                cts.setModelRunId(-1);
                cts.setTimeSeriesIdentifier(tsid);

                // NOTE: In OpenTsdb, tasklist values are always in storage units.
                cts.setUnitsAbbr(tsid.getStorageUnits());
                if (((TimeSeriesDb)db).fillDependentCompIds(cts, applicationId, this) == 0)
                {
                    log.warn("Deleting tasklist rec for '{}' because no dependent comps.", tsid.getUniqueString());
                    if (badRecs != null)
                    {
                        badRecs.add(rec.getRecordNum());
                    }
                    return;
                }

                try
                {
                    dataCollection.addTimeSeries(cts);
                }
                catch(decodes.tsdb.DuplicateTimeSeriesException ex)
                { // won't happen -- already verified it's not there.
                    log.atError()
                       .setCause(ex)
                       .log("Unlikely error has happened.");
                }
            }
            catch(NoSuchObjectException ex)
            {
                log.warn("Deleting tasklist rec for non-existent ts_code {}", rec.getSdi());
                if (badRecs != null)
                {
                    badRecs.add(rec.getRecordNum());
                }
                return;
            }
        }
        if (rrhandle != null)
            rrhandle.addRecNum(rec.getRecordNum());

        // Construct timed variable with appropriate flags & add it.
        TimedVariable tv = new TimedVariable(rec.getValue());
        tv.setTime(rec.getTimeStamp());
        tv.setFlags((int)rec.getQualityCode());

        if (!rec.isDeleted() && !rec.isValueWasNull())
        {
            VarFlags.setWasAdded(tv);
            cts.addSample(tv);
            // Remember which tasklist records are in this timeseries.
            cts.addTaskListRecNum(rec.getRecordNum());
            log.trace("Added value {} to time series '{}' flags=0x{} cwms qualcode=0x{}",
                      tv, cts.getTimeSeriesIdentifier().getUniqueString(),
                      Integer.toHexString(tv.getFlags()),Long.toHexString(rec.getQualityCode()));
        }
        else
        {
            VarFlags.setWasDeleted(tv);
            log.trace("Discarding deleted value {} for time series '{}' flags=0x{} cwms qualcode=0x{}",
                      tv.toString(), cts.getTimeSeriesIdentifier().getUniqueString(),
                      Integer.toHexString(tv.getFlags()), Long.toHexString(rec.getQualityCode()));
        }
    }
}
