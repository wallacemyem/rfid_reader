/**
 *  Name: 
 *  	Database - Our RFID reader needs a simple database. The original version use a google spreadsheet
 *  	and tried to update it in real time. That had a number of disadvantages, notably the reliability 
 *  	of the school's Internet access (problems with the Sonic firewall) and performance. 
 *  	The version of attendance system will use a local database. We can copy the data to a google
 *  	spreadsheet as needed. 
 *  
 *  	What DB? We want a simple embedded DB. There are MANY with varying feature and complexity. 
 *  	We choose KISS here and are using standard Berkley DB (cause that's all we need). Basically
 *  	B-tree indexes access. 
 *  
 *  	Our table will be indexed by day (primary key) and username (secondary key) 
 *  	The "value" data for each day
 *  		- username
 *  		- time in
 *  		- time out
 *  		- total time for today
 *  	For example:
 *  		Key: 					 Time In   Time Out	   Check-ins | Time Today
 *  		1/12/2017 Wolfe, Peter | 9:00	 | 18:00 	|  1		 | 9:00:00
 *  
 * 		If you log in/out multiple times on the same day there are several use-cases:
 * 		1) Single login/logout:
 * 			- The day's first login updates Time In
 * 			- A subsequent logout updates the number of check-ins
 * 			  and the Time Today (elapsed time) and clears the in/out fields.
 * 		2) Multiple login/logouts on the same day 
 * 			- The day's 1st login updates Time In
 * 			- A 1st logout updates, check-ins, Time Today and clears the in/out fields.
 *  		- Repeat for subsequent login/out pairs, adding to check-ins, Time Today each time.
 *  	3) Login in with no logout
 *  		- User's time-in updated but gets no credit (check-ins and Time Today never updated)
 *  		- Pjw: TODO: if student realizes, need to allow a manual (mentor approved) udpate. See below. 
 *  	4) Logout with no login
 *  		- User gets no credit. (check-ins and Time Today never updated). 
 *  		- Pjw: TODO: we should warn the user of this error and allow a manual (mentor approved)
 *  		  update of the database. Need to create a code path to allow that
 *  		  (e.g. some CLI DB maintenance tasks)
 *		PJW: The above implies we don't really need a "Time Out" field. For the single login/logout
 *		case it can be computed. The the multi-login/logout case it really applies only to the last
 *		logout so is not really useful. For the logout with no login case, it can be used forensically
 *		to make sure students are staying honest with their claims so leave it for now. 
 *
 *		PJW: Modeling question. Above I modeled this as a day has a set of users. 
 *		Could have done a user has a set of days. Not sure it really matters (?). Still have
 *		to index by a primary and secondary key. However, I'm not a DB guy...
 *
 *		There is an invaluable Berkely DB tutorial here: 
 *			http://www.oracle.com/technetwork/testcontent/o27berkeleydb-100623.html
 * 
 *  	There are command line tools to load and dump the DB
 *  	See: http://docs.oracle.com/cd/E17277_02/html/java/com/sleepycat/je/util/DbDump.html
 *  	for an example:
 *   		java { com.sleepycat.je.util.DbDump |
 *       		-jar je-<version>.jar DbDump }
 *  			-h <dir>           # environment home directory
 * 				[-f <fileName>]     # output file, for non -rR dumps
 * 				[-l]                # list databases in the environment
 * 				[-p]                # output printable characters
 * 				[-r]                # salvage mode
 * 				[-R]                # aggressive salvage mode
 * 				[-d] <directory>    # directory for *.dump files (salvage mode)
 * 				[-s <databaseName>] # database to dump
 * 				[-v]                # verbose in salvage mode
 * 				[-V]                # print JE version number	 * 
 * 
 * 		Example DB dump command:
 * 		   java -jar ..\..\lib\je-7.4.5.jar DbDump -h . -s persist#RFIDStore#rfid_reader.DatabaseDay -o
 */


package rfid_reader;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date; // Apparently Berkeley DB cannot persist Java8 MonthDay objects. So use the old date object
import java.text.SimpleDateFormat;
import java.time.*;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.persist.EntityCursor;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;
import com.sleepycat.persist.SecondaryIndex;
import com.sleepycat.persist.StoreConfig;




public class Database {

	private Environment env;		// Berkley DB environment is a set of files in the DB directory
	private EntityStore store;		// DB store for managing entity objects
	//private PrimaryIndex<MonthDay, DatabaseDay> dayByDate;
	private PrimaryIndex<String, DatabaseDay> dayByDate;
	
	//private SecondaryIndex<DatabaseUserTimelog, MonthDay, DatabaseDay> userByDay;
	//private SecondaryIndex<DatabaseUserTimelog, Date, DatabaseDay> userByDay;
	//private PrimaryIndex<String, DatabaseUserTimelog> userByName;
	
    public void DBinit() throws DatabaseException {
    	
    	
        EnvironmentConfig envConfig = new EnvironmentConfig();
    	final File db_dir = new File(Constants.DATABASE_DIR);
    	
        db_dir.mkdirs();						// Create dir if it doesn't exist
        
        /* Open a transactional Berkeley DB Environment. */
        envConfig.setAllowCreate(true);
        envConfig.setTransactional(true);
        env = new Environment(db_dir, envConfig);
        
        
        /* Open a transactional EntityStore. */
        StoreConfig storeConfig = new StoreConfig();
        storeConfig.setAllowCreate(true);
        storeConfig.setTransactional(true);
        store = new EntityStore(env, "RFIDStore", storeConfig);

        /* Initialize the index objects. */
        //dayByDate 	= store.getPrimaryIndex(MonthDay.class, DatabaseDay.class);
        //dayByDate 	= store.getPrimaryIndex(Date.class, Date.class);
        dayByDate 	= store.getPrimaryIndex(String.class, DatabaseDay.class);
        //userByDay 	= store.getSecondaryIndex(dayByDate, DatabaseUserTimelog.class, "name" );
        //userByName 	= store.getPrimaryIndex(String.class, DatabaseUserTimelog.class);
                    
    } // end DBinit  	
   
    public Constants.LoginType write(String user) throws DatabaseException {

    	
    	/*
         * Begin a transaction that will be used to atomically commit all
         * operations in this method.  Note that if no transaction were used,
         * auto-commit would be used for each individual operation.
         */
        Transaction txn = env.beginTransaction(null, null);
        boolean success = false;
        Constants.LoginType login_type;
        
        try { 

        	String today = null; 
        	

        	//MonthDay today = MonthDay.now();
        	Date date = new Date();					// Timestamp for right now including hh:mm:ss
        	
        	SimpleDateFormat sd = new SimpleDateFormat("yyyy/MM/dd");
        	today = sd.format(date);			// This is just the year/month/day
        	Debug.log("Today's database day is: " + today.toString());
        				
        	//DatabaseUserTimelog user_timelog = new DatabaseUserTimelog(user, ZonedDateTime.now()); 
        	// TODO: Need to look up user to see if he already exists to today. 
        	// For this basic testing just create him and write to DB
        	DatabaseUserTimelog user_timelog = new DatabaseUserTimelog(user, date); 
        	
        	
        	DatabaseDay dd = dayByDate.get(today);		// Is there an existing db record for today?
        	if (dd == null) {
        		dd = new DatabaseDay(today);
        		Debug.log("New day for DB");
        		
        	} else {
        		Debug.log("Existing day in DB");
        	}
        	        	
        	login_type = dd.setUser_timelog(user, date);

        	
        	//dayByDate.put(txn, dd); 
        	dayByDate.put(txn, dd); 

        	//userByDay.put(txn, user_timelog);
        	success = true; 
        } finally {
        	/*
             * The transaction must be committed or aborted before this method
             * exits to avoid resource leaks.  Success will be false if an
             * exception occurs, in which case we abort the transaction.
             */
            if (success) {
                txn.commit();
            } else {
                txn.abort();
            }       	
        }
    	
        return login_type; 
        
    } // end writeData

    
    /**
     * 
     * 
     * @throws DatabaseException
     */
    public void queryDB() throws DatabaseException {
    
    } // end queryDB

    /**
     * Read the DB to create reports (or emit CSV files for use with excel)
     * 
     * @throws DatabaseException
     */	
    public void reportFromDB() throws DatabaseException {
        
    	EntityCursor<DatabaseDay> dds = dayByDate.entities();
    	
    	try {
	    	for (DatabaseDay dd : dds) {
	    		System.out.println("DB dump: ");
	    		System.out.println(dd);
	    	}
    	} finally {
    		dds.close();
    	}
    	
    	
    } // end reportFromDB

    
    
    public void close() throws DatabaseException {
		/* Always close the store first, then the environment. */
        store.close();
        env.close();
	}
    
} // end class Database
