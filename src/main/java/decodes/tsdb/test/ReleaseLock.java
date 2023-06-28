package decodes.tsdb.test;

import ilex.util.Logger;

import java.util.List;
import java.util.Optional;

import opendcs.dai.LoadingAppDAI;

import decodes.tsdb.*;
import decodes.util.DecodesException;
import decodes.db.Constants;

public class ReleaseLock 
	extends TsdbAppTemplate
{
	public ReleaseLock()
	{
		super(null);
	}

	protected void runApp()
		throws Exception
	{
		if (getAppId() == Constants.undefinedId)
		{
			System.err.println(
				"-a <appName> argument required -- No action taken!");
			return;
		}
		// Note, the -a arg will have us connect to the database as the
		// desired application.
		
		try(LoadingAppDAI loadingAppDAO = theDb.makeLoadingAppDAO();)
		{
			Optional<TsdbCompLock> lock = loadingAppDAO.getLockForAppId(getAppId());
			if(lock.isPresent())
			{
				loadingAppDAO.releaseCompProcLock(lock.get());
			}
			else
			{
				System.out.println("Application is not running.");
			}
		}
		catch(DbIoException ex)
		{
			
		}
	}
	
	public void initDecodes()
		throws DecodesException
	{
	}
	
	public static void main(String args[])
		throws Exception
	{
		ReleaseLock tp = new ReleaseLock();
		tp.execute(args);
	}
}
