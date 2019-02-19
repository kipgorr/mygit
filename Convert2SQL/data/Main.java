import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;
//Snowflake imports
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/*
 *  Execute :: Provide two parameters, 
 *  1. local vs aws vs azure
 *  2. path to file 
 * *   - Linux/MacOS:
 *     java -cp .:snowflake-jdbc-3.6.9.jar Main local "/Users/ikarbovskyy/eclipse-workspace/Convert2SQL/data/sample1.csv"
 *     put file:////Users/ikarbovskyy/eclipse-workspace/Convert2SQL/data/sample1.csv @~/staged;
 *     put file:////Users/ikarbovskyy/eclipse-workspace/Convert2SQL/data/sample2.csv @~/staged;
 *     copy into sample1 from @~/staged;
 * */



public class Main 
{
	public static boolean  bVerbose = true;//suppress login output
	public static String columnDefName = "";
	public static int iHowManyLines2check = 2;
	public static boolean bProcessHeaderFirstLine = true;


	public static boolean isThisDateValid(String dateToValidate, String dateFromat){

		if(dateToValidate == null){
			return false;
		}

		SimpleDateFormat sdf = new SimpleDateFormat(dateFromat);
		sdf.setLenient(false);

		try {

			//if not valid, it will throw ParseException
			Date date = sdf.parse(dateToValidate);
			System.out.println(date);

		} catch (ParseException e) {

			//e.printStackTrace();
			return false;
		}
		return true;
	}

	public static String getExtension(String fileName) {
		char ch;
		int len;
		if(fileName==null || 
				(len = fileName.length())==0 || 
				(ch = fileName.charAt(len-1))=='/' || ch=='\\' || //in the case of a directory
				ch=='.' ) //in the case of . or ..
			return "";
		int dotInd = fileName.lastIndexOf('.'),
				sepInd = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
		if( dotInd<=sepInd )
			return "";
		else
			return fileName.substring(dotInd+1).toLowerCase();
	}

	private static String stripExtension (String fileNameWithPath) {

		Path p = Paths.get(fileNameWithPath);
		String fileName = p.getFileName().toString();
		// Handle null case specially.

		if (fileName == null) return null;

		// Get position of last '.'.

		int pos = fileName.lastIndexOf(".");

		// If there wasn't any '.' just return the string as is.

		if (pos == -1) return fileName;

		// Otherwise return the string, up to the dot.

		return fileName.substring(0, pos);
	}

	private static Connection getConnection(HashMap<String, String> connParams)
			throws SQLException {
		try {
			Class.forName("net.snowflake.client.jdbc.SnowflakeDriver");
		} catch (ClassNotFoundException ex) {
			System.err.println("Driver not found");
		}

		// build connection properties // testHashMap2.get("key1")
		Properties properties = new Properties();
		properties.put("user", connParams.get("userSF"));        // replace "" with your user name
		properties.put("password", connParams.get("passwordSF"));    // replace "" with your password
		properties.put("account", connParams.get("accountSF"));     // replace "" with your account name
		properties.put("warehouse", connParams.get("warehouseSF"));   // replace "" with target warehouse name
		properties.put("db", connParams.get("dbSF"));          // replace "" with target database name
		properties.put("schema", connParams.get("schemaSF"));      // replace "" with target schema name
		String regionSF = connParams.get("regionSF");
		String regionSFlink = "";
		if(!regionSF.equals("default"))
		{
			properties.put("region", connParams.get("regionSF"));
			regionSFlink = "." + regionSF;
		}   
		// replace <account_name> with the name of your account, as provided by Snowflake
		// replace <region_id> with the name of the region where your account is located (if not US West)
		// remove region ID segment (not needed) if your account is located in US West
		String connectStr = "jdbc:snowflake://"+connParams.get("accountSF")+ regionSFlink + ".snowflakecomputing.com";
		return DriverManager.getConnection(connectStr, properties);
	}

	public static void main(String[] args) throws IOException, SQLException 
	{
		setConfig();
		HashMap<String, String> connParams = getProperties();
		
		HashMap<String, String> paramsInCSV = new HashMap<String, String>();
		String fileNameWithPath = "";
		if(args.length == 0)
		{
			System.out.println("Only CSV for now. Provide two parameters. \nFirst: local or aws or azure.\nSecond: path to file.");
			return;
		}
		else if(args[0].equals("local")){
			for(int i = 0; i < args.length; i++) 
			{
				System.out.println(args[i]);
			}
			fileNameWithPath = args[1];
		}
		//Temp way to bypass without AWS & Azure
		if(!args[0].equals("local"))
		{
			System.out.println("Only 'local' supported in current version.");
			return;
		}

		String extension = getExtension(fileNameWithPath);
		outputLogin(bVerbose, extension);
		String createTableSql = "";
		if(extension.equalsIgnoreCase("csv"))
		{
			paramsInCSV = processCSV(fileNameWithPath);
		}
		processData2Snow(connParams, paramsInCSV);
	}

	private static void processData2Snow(HashMap<String, String> connParams, HashMap<String, String> paramsInCSV)
			throws SQLException {
		System.out.println("Create JDBC connection");
		Connection connection = getConnection(connParams);
		System.out.println("Done creating JDBC connection\n");

		// create statement
		System.out.println("Create JDBC statement");
		Statement statement = connection.createStatement();
		System.out.println("Done creating JDBC statement\n");

		// create a table
		System.out.println("Create table");
		statement.executeUpdate(paramsInCSV.get("createTableSql"));
		statement.close();
		System.out.println("Done creating demo table\n");
		
		System.out.println("put file");
		statement.execute("put file:////"+paramsInCSV.get("fileNameWithPath")+" @~/staged");
		statement.close();
		System.out.println("Done put file\n");
		
		System.out.println("copy into");
		statement.execute("copy into "+paramsInCSV.get("fileName")+" from @~/staged file_format = (type = csv field_delimiter = ',' skip_header = 1);");
		statement.close();
		System.out.println("Done copy into\n");
		
		System.out.println("Crlear stage");
		statement.execute("remove @~/staged pattern='.*.csv.gz';");
		statement.close();
		System.out.println("Done Crlear stage\n");
	}

	private static void setConfig() {
		//String rootPath = Thread.currentThread().getContextClassLoader().getResource("").getPath();
		//System.out.println(rootPath);

		//String defaultConfigPath = rootPath + "default.properties";
		//Properties defaultProps = new Properties();


		//Properties prop = new Properties();
		/*
		 * OutputStream output = null; // output = new
		 * FileOutputStream("config.properties");
		 * 
		 * prop.setProperty("userSF", "IKarbov"); // replace "" with your user name
		 * prop.setProperty("passwordSF", "Sekretik_27"); // replace "" with your
		 * password prop.setProperty("accountSF", "aws_cas1"); // replace "" with your
		 * account name prop.setProperty("warehouseSF", "DW_IK"); // replace "" with
		 * target warehouse name prop.setProperty("dbSF", "IGORKA_DB"); // replace ""
		 * with target database name prop.setProperty("schemaSF", "JSON_TEST"); // // //
		 * set the properties value prop.setProperty("verbose", "false");
		 * prop.setProperty("columnDefName", "Column_");
		 * prop.setProperty("csvDelimiters", ",#|#\t");
		 * prop.setProperty("preferedLocation", "local");
		 * prop.setProperty("howManyLines2check", "10"); prop.setProperty("dateFormat",
		 * "MM/dd/yyyy#yyyy/MM/dd#yyyy-MM-dd#MM-dd-yyyy#dd-MM-YYYY#yyyy-MM-dd HH:mm:ss#yyyy-MM-dd HH:mm:ss.SSS#yyyy-MM-dd HH:mm:ss.SSS"
		 * ); //bProcessHeaderFirstLine prop.setProperty("processHeaderFirstLine",
		 * "false"); // save properties to project root folder prop.store(output, null);
		 */
	}

	private static HashMap<String, String> getProperties() throws FileNotFoundException, IOException {
		Properties prop = new Properties();
		InputStream input = new FileInputStream("config.properties");
		// load a properties file
		prop.load(input);
		HashMap<String, String> connParams = new HashMap<String, String>();
		// get the property value and print it out
		try {
			bVerbose = Boolean.parseBoolean(prop.getProperty("verbose"));
			columnDefName = prop.getProperty("columnDefName");
			iHowManyLines2check = Integer.parseInt(prop.getProperty("howManyLines2check"));
			bProcessHeaderFirstLine = Boolean.parseBoolean(prop.getProperty("processHeaderFirstLine"));
			connParams.put("userSF",prop.getProperty("userSF"));
			connParams.put("passwordSF",prop.getProperty("passwordSF"));
			connParams.put("warehouseSF", prop.getProperty("warehouseSF"));
			connParams.put("dbSF",prop.getProperty("dbSF"));
			connParams.put("schemaSF",prop.getProperty("schemaSF"));
			connParams.put("regionSF",prop.getProperty("regionSF"));//accountSF
			connParams.put("accountSF",prop.getProperty("accountSF"));
		} catch (NumberFormatException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			outputLogin(true, "Incorrect congif settings::" + e.toString());
			//return;
		}
		return connParams;
	}



	private static void outputLogin(boolean bVerbose, String extension) {
		if(bVerbose) System.out.println( extension);
	}

	private static void outputLogin(String extension) {
		if(bVerbose) System.out.println("log::" + extension);
	}

	private static HashMap<String, String> processCSV(String fileNameWithPath) throws FileNotFoundException, IOException {

		HashMap<String, String> paramsInCSV = new HashMap<String, String>();
		paramsInCSV.put("fileNameWithPath", fileNameWithPath);
		String fileName = stripExtension(fileNameWithPath);
		paramsInCSV.put("fileName", fileName);

		outputLogin("fileNameWithPath::" + fileNameWithPath);
		outputLogin("fileName::" + fileName);
		FileReader file2process = new FileReader(fileNameWithPath);

		BufferedReader CSVFile = new BufferedReader(file2process);
		boolean boolProcessHeaderFirstLine = bProcessHeaderFirstLine;//Need logic if no HEADER in CSV
		int iCounterLines = 0;//param how many lines to process for evaluation
		//int iHowManyLines2check = 2;//No less then 2 - TWO
		List<String> listColumns = new ArrayList<String>(); 
		List<String> listTypes = new ArrayList<String>(); 
		StringBuilder strColumn = new StringBuilder();
		strColumn.append("CREATE OR REPLACE TABLE "+ fileName +" \n(\n");// table name as file name
		String delimiter = ",";//default delimiter

		String dataRow = CSVFile.readLine();

		while (dataRow != null && iCounterLines < iHowManyLines2check)
		{
			boolean isDateValdated = false;
			if(iCounterLines == 0)
			{
				if (boolProcessHeaderFirstLine) 
					delimiter = fromFirstLineGetColumns(dataRow, listColumns, delimiter);//Header naming Columns
				else
				{
					delimiter = fromFirstLineGetColumnsNoHeader(dataRow, listColumns, delimiter);
					//Auto naming Columns
				}
			}
			else 
			{
				String[] dataArray = dataRow.split(delimiter);
				evaluateDataTypesFromColumns(listTypes, isDateValdated, dataArray);
			}
			iCounterLines++;
			dataRow = CSVFile.readLine();
		}
		
		for(int i = 0; i < listColumns.size(); i++)
		{
			strColumn.append(listColumns.get(i) + " " + listTypes.get(i)  + ","+ "\n");
			//listColumns.add(listColumns(i));
		}
		strColumn.setLength(strColumn.length() - 2);
		strColumn.append("\n" + ")");
		outputLogin(true,strColumn.toString());
		CSVFile.close();
		paramsInCSV.put("delimiter", delimiter);
		paramsInCSV.put("createTableSql", strColumn.toString());
		return paramsInCSV;//strColumn.toString();
	}

	private static void evaluateDataTypesFromColumns(List<String> listTypes, boolean isDateValdated,
			String[] dataArray) {
		for(int i = 0; i < dataArray.length; i++)
		{
			String currColumn = removeDquotes(dataArray[i]);
			Scanner input = new Scanner(currColumn);
			outputLogin("dataArray[i]::" + currColumn);

			if (input.hasNextInt())
			{
				outputLogin(currColumn + "::"+ "This input is of type Integer.");
				listTypes.add("Integer");
			}

			else if (input.hasNextFloat())
			{ 
				outputLogin(currColumn + "::"+"This input is of type Float.");
				listTypes.add("Float");
			}


			else if (input.hasNextDouble()) 
			{
				outputLogin(currColumn + "::"+"This input is of type Double."); 
				listTypes.add("Double");
			}

			else if (input.hasNextBoolean())
			{
				outputLogin(currColumn + "::"+"This input is of type Boolean.");  
				listTypes.add("Boolean");
			}

			else if (input.hasNextLong())
			{
				outputLogin(currColumn + "::"+"This input is of type Long."); 
				listTypes.add("Long");
			}

			else  if (currColumn.contains("/") || currColumn.contains("-"))
			{
				isDateValdated = false;
				isDateValdated = validateDateColumn(isDateValdated, currColumn);
				//outputLogin(date.toString() + "::"+"This input is of type DATE.");
				if(isDateValdated)
				{
					listTypes.add("DATE");
				}
				else 
					listTypes.add("VARCHAR");
			}

			else if (input.hasNextLine() && !isDateValdated)
			{
				outputLogin(dataArray[i] + "::"+"This input is of type string."); 
				listTypes.add("VARCHAR");
			}
			input.close();
		}
	}

	private static boolean validateDateColumn(boolean isDateValdated, String currColumn) {	
		//boolean isDateValdated = false; add Reading from Config
		String[] dateFormat = new String[] {"MM/dd/yyyy", "yyyy/MM/dd", "yyyy-MM-dd", "MM-dd-yyyy","dd-MM-YYYY","yyyy-MM-dd HH:mm:ss","yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss.SSS Z"};//new String[]{ "a", "b", "c" } );

		for (String sFormat: dateFormat) {           
			//Do your stuff here
			outputLogin(sFormat); 
			SimpleDateFormat sDataFormat = new SimpleDateFormat(sFormat);//dd-MM-YYYY
			if(!isDateValdated)
				isDateValdated = parseDateWithParam(currColumn, sDataFormat);
		}

		return isDateValdated;
	}

	private static boolean parseDateWithParam(String currColumn, SimpleDateFormat formatDate) {
		boolean isDateValdated;
		Date date = null;
		try {
			isDateValdated = true;
			date  = formatDate.parse ( currColumn );

		} catch (ParseException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
			//outputLogin(dataArray[i] + "::"+"This input is of type 1NOT DATE.");
			isDateValdated = false;
		}
		return isDateValdated;
	}

	private static String fromFirstLineGetColumns(String dataRow, List<String> listColumns, String delimiter) {
		if(dataRow.contains("|"))
		{delimiter = "|";}
		else if(dataRow.contains("\t"))
		{delimiter = "\t";}
		//else if(dataRow.contains("")){}

		String[] dataArray = dataRow.split(delimiter);
		for(int i = 0; i < dataArray.length; i++)
		{
			String currColumn = removeDquotes(dataArray[i]);
			listColumns.add(currColumn);
		}
		return delimiter;
	}

	private static String removeDquotes(String currColumnFrom) {
		String currColumn;
		if(currColumnFrom.startsWith("\"") && currColumnFrom.endsWith("\""))
		{
			currColumn = currColumnFrom.substring(1, currColumnFrom.length()-1);
			//System.out.println("Q & !Q::" + currColumnFrom + ":"+ currColumn);
			outputLogin(bVerbose, "Q & !Q::" + currColumnFrom + ":"+ currColumn);
			
		}
		else
		{currColumn = currColumnFrom;}
		return currColumn;
	}

	private static String fromFirstLineGetColumnsNoHeader(String dataRow, List<String> listColumns, String delimiter) {
		if(dataRow.contains("|"))
		{delimiter = "|";}
		else if(dataRow.contains("\t"))
		{delimiter = "\t";}
		//else if(dataRow.contains("")){}

		String[] dataArray = dataRow.split(delimiter);
		for(int i = 0; i < dataArray.length; i++)
		{
			//strColumn.append(dataArray[i] +","+ "\n");
			listColumns.add(columnDefName + i);
		}
		return delimiter;
	}
}