package decodes.tsdb.test;

import ilex.util.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

	@Override
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
			final ExecutorService executor = Executors.newSingleThreadExecutor();
			final Future<Optional<TsdbCompLock>> future = 
					executor.submit(()->loadingAppDAO.getLockForAppId(getAppId()));
			Optional<TsdbCompLock> lock = future.get(1, TimeUnit.SECONDS);
			if(lock.isPresent())
			{
				loadingAppDAO.releaseCompProcLock(lock.get());
			}
			else
			{
				System.out.println("Application is not running.");
			}
		}
		catch(TimeoutException | DbIoException ex)
		{
			System.out.println("Unable to connect to database or get result promptly from database. Manually killing process.");
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
