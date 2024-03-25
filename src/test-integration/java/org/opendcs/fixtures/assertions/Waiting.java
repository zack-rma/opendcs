package org.opendcs.fixtures.assertions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.AssertionFailureBuilder;

import opendcs.util.functional.ThrowingFunction;

public class Waiting
{
    public static <R> R waitForResult(Function<R, Boolean> resultChecker,
                                      ThrowingFunction<Long, R, Exception> resultProvingTask,
                                      long waitFor, TimeUnit waitForUnit,
                                      long checkEvery, TimeUnit checkEveryUnit) throws Exception
    {
        R ret;
        long start = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        long interval = checkEveryUnit.toMillis(checkEvery);
        long waitLength = waitForUnit.toMillis(waitFor);
        do
        {
            now = System.currentTimeMillis();
            ret = resultProvingTask.accept(now);
            if(resultChecker.apply(ret) == false)
            {
                try
                {
                    Thread.sleep(interval);
                }
                catch (InterruptedException ex)
                {
                    /* do nothing, just begin loop again */
                }
            }
        }
        while(resultChecker.apply(ret) == false && (now - start) < waitLength);
        return ret;
    }

    public static <R> void assertResultWithinTimeFrame(
                                              Function<R, Boolean> resultChecker,
                                              ThrowingFunction<Long, R, Exception> resultProvidingTask,
                                              long waitFor, TimeUnit waitForUnit, long checkEvery,
                                              TimeUnit checkEveryUnit, String message)
                    throws Exception
    {
        R result = waitForResult(resultChecker, resultProvidingTask, waitFor, waitForUnit, checkEvery, checkEveryUnit);
        if (!resultChecker.apply(result))
        {
             AssertionFailureBuilder.assertionFailure()
                                    .reason("Desired result was not provided within the expected time frame.")
                                    .message(message)
                                    .actual(result)
                                    .buildAndThrow();
        }
    }   
}
