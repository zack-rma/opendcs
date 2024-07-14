package decodes.cwms;

import decodes.db.DatabaseException;
import decodes.db.DatabaseIO;

/**
 reference/compare some core CWMS Database functionality while
 investigating using https://jdbi.org/

   - connection pooling
   - time to load database in DecodesInterface
   -
 */
public class CwmsDatabaseTest {


    public static void main(String[] args) throws DatabaseException {
        String dbLocation = System.getenv("TEST_CWMS_CONNECTION");
        DatabaseIO io = new decodes.cwms.CwmsSqlDatabaseIO(dbLocation);

    }

}
