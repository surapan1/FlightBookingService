package edu.uw.cs;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.xml.bind.*;

/**
 * Runs queries against a back-end database
 */
public class Query {
  // DB Connection
  private Connection conn;
  
  private boolean loggedIn = false;
  private ArrayList<String> customersLoggedIn;
  private ArrayList<Itinerary> itineraries;
  private static int nextRSVID;
  private ResultSet searchResults, reservationResults, userFlight;
  private int numItin;
  
  // Password hashing parameter constants
  private static final int HASH_STRENGTH = 65536;
  private static final int KEY_LENGTH = 128;
  
  private static final String TEST = "SELECT * FROM Reservations; ";
  private PreparedStatement testStatement;

  //Clear
  //private static final String CLEAR = "DELETE FROM Customers; "
  		//+ "	DELETE FROM Reservations; DELETE FROM SeatsSold;";
  //private PreparedStatement clearStatement;
  //login user
  private static final String LOGIN  = "SELECT username FROM Users "
  		+ "WHERE username = ? AND passwordHash = ? AND passwordSalt = ?";
  private PreparedStatement loginStatement;
  // Check if user exists
  private static final String CHECK_USER_EXIST = "SELECT * \n FROM Customers AS C \n WHERE C.username = ?";
  private PreparedStatement checkUserStatement;
  //Create new user
  private static final String CREATE_USER = "INSERT INTO Customers \n VALUES(?,?,?,?);";
  private PreparedStatement createUserStatement;
  //Search one-hop
  private static final String SEARCH_ONE_HOP = "SELECT TOP (?) fid, day_of_month,carrier_id,flight_num,origin_city,dest_city,actual_time,capacity,price \n FROM Flights \n "
			+ "WHERE origin_city = ? AND dest_city = ? AND day_of_month =  ? AND canceled = 0 \n ORDER BY actual_time, fid ASC;";
  private PreparedStatement searchOneHopStatement;
  //Count one-hop
  private static final String COUNT_ONE_HOP = "SELECT COUNT (*) FROM Flights "
          + "WHERE origin_city = '?' AND dest_city = '?' AND day_of_month = ? AND canceled = 0";
  private PreparedStatement countOneHopStatement;
  //Search two-hop
  private static final String SEARCH_TWO_HOP = " WITH CityGoes AS(SELECT day_of_month,carrier_id,flight_num,origin_city,dest_city,actual_time,capacity,price,fid\n"
			+ "FROM Flights AS P \n" + "WHERE P.origin_city = ? AND P.day_of_month = ? AND P.canceled = 0)\n" + "\n"
			+ "SELECT TOP (?) CG.fid, CG.day_of_month,CG.carrier_id, CG.flight_num,CG.origin_city,\n"
			+ "					CG.dest_city AS indirect_city, CG.actual_time AS first_actual_time, CG.capacity, CG.price, P.fid AS second_fid, P.carrier_id AS second_carrier_id,  \n"
			+ "					P.flight_num AS second_flight_num,P.dest_city,P.actual_time AS second_actual_time,P.capacity AS second_capacity,P.price AS second_price\n"
			+ "FROM CityGoes AS CG, Flights AS P \n"
			+ "WHERE P.origin_city = CG.dest_city AND P.dest_city = ? AND P.day_of_month = ? and P.canceled = 0\n"
			+ "ORDER BY (CG.actual_time + P.actual_time), CG.fid, P.fid;";
  private PreparedStatement searchTwoHopStatement;
  private static final String checkDoubleBookedSQL = "SELECT * \n FROM Reservations AS R \n" + "WHERE R.customer = ? AND R.date = ?; ";
  private PreparedStatement checkDoubleBookStatement;
  private static final String addNewReservationSQL = "INSERT INTO Reservations VALUES(?,?,?,?,?,?,?);";
  private PreparedStatement addNewReservationStatement;
  private static final String getNextRSVIDSQL = "SELECT MAX(R.rsvid) AS rsvid \n FROM Reservations AS R;";
  private PreparedStatement getNextRSVIDStatement;
  private static final String getExistingReservationSQL = "SELECT * \n FROM Reservations AS R \n WHERE " + "R.rsvid = ?;";
  private PreparedStatement getExistingReservationStatement;
  private static final String getCustomerSQL = "SELECT * \n FROM Customers AS C \n WHERE C.username = ?;";
  private PreparedStatement getCustomerStatement;
  private static final String updateCustomerAcctBalanceSQL = "UPDATE Customers \n SET acctBalance = ? \n" + "WHERE username = ?;";
  private PreparedStatement updateCustomerAcctBalanceStatement;
  private static final String setReservationPaidSQL = "UPDATE Reservations \n SET paid = 1 \n WHERE rsvid = ?;";
  private PreparedStatement setReservationPaidStatement;
  private static final String getCustomerReservationsSQL = "SELECT * \n FROM Reservations AS R \n WHERE R.customer = ?;";
  private PreparedStatement getCustomersReservationsStatement;
  private static final String getFlightByFIDSQL = "SELECT * \n FROM Flights AS F \n WHERE F.fid = ?;";
  private PreparedStatement getFlightByFIDStatement;
  private static final String deleteReservationSQL = "DELETE \n FROM Reservations \n WHERE rsvid = ?;";
  private PreparedStatement deleteReservationStatement;
  private static final String getFlightSeatsSoldSQL = "SELECT S.seatsSold \n FROM SeatsSold AS S \n WHERE " + "S.fid = ?;";
  private PreparedStatement getFlightSeatsSoldStatement;
  private static final String updateSeatsSoldSQL = "UPDATE SeatsSold \n SET seatsSold = seatsSold + ? \n"
			+ "WHERE fid = ? \n IF @@ROWCOUNT = 0 \n INSERT INTO SeatsSold (fid, seatsSold) \n " + "VALUES (?,?);";
  private PreparedStatement updateSeatsSoldStatement;
  
  private PreparedStatement clearCustomersTableDataStatement;
  private PreparedStatement clearSeatsSoldTableDataStatement;
  private PreparedStatement clearReservationsTableDataStatement;
  private PreparedStatement addNewCustomerStatement;

  /**
   * Establishes a new application-to-database connection. Uses the
   * dbconn.properties configuration settings
   * 
   * @throws IOException
   * @throws SQLException
 * @throws ClassNotFoundException 
   */
  public void openConnection() throws IOException, SQLException {
    // Connect to the database with the provided connection configuration
    Properties configProps = new Properties();
    configProps.load(new FileInputStream("dbconn.properties"));
    String serverURL = configProps.getProperty("hw1.server_url");
    String dbName = configProps.getProperty("hw1.database_name");
    String adminName = configProps.getProperty("hw1.username");
    String password = configProps.getProperty("hw1.password");
    String connectionUrl = String.format("jdbc:sqlserver://%s:1433;databaseName=%s;user=%s;password=%s", serverURL,
        dbName, adminName, password);
    conn = DriverManager.getConnection(connectionUrl);

    // By default, automatically commit after each statement
    conn.setAutoCommit(false);

    // By default, set the transaction isolation level to serializable
    conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
  }

  /**
   * Closes the application-to-database connection
   */
  public void closeConnection() throws SQLException {
    conn.close();
  }

  /**
   * Clear the data in any custom tables created.
   * 
   * WARNING! Do not drop any tables and do not clear the flights table.
   */
  public void clearTables() {
    try {
    	conn.setAutoCommit(true);
    	clearCustomersTableDataStatement.executeUpdate();
		clearReservationsTableDataStatement.executeUpdate();
		clearSeatsSoldTableDataStatement.executeUpdate();
		// clear any local data here too
		this.customersLoggedIn.clear();
		nextRSVID = 1;
		this.loggedIn = false;
		conn.setAutoCommit(false);
	} catch (Exception e) {
		System.out.println("problem clearing tables");
		e.printStackTrace();
    }
  }

  /*
   * prepare all the SQL statements in this method.
   */
  public void prepareStatements() throws SQLException {    
	  //Clear statement
	  //clearStatement = conn.prepareStatement(CLEAR);
	  
	  testStatement = conn.prepareStatement(TEST);
	  
	  //Login statement
	  loginStatement = conn.prepareStatement(LOGIN);
	  
	  //Check user statement
	  checkUserStatement = conn.prepareStatement(CHECK_USER_EXIST);
    
	  //Create user statement
	  createUserStatement = conn.prepareStatement(CREATE_USER);
	  
	  //Search one-hop statement
	  searchOneHopStatement = conn.prepareStatement(SEARCH_ONE_HOP);
	  
	  //Count one-hop statement
	  countOneHopStatement = conn.prepareStatement(COUNT_ONE_HOP);
	  
	  //Search one-hop statement
	  searchTwoHopStatement = conn.prepareStatement(SEARCH_TWO_HOP);
	  
	  checkDoubleBookStatement = conn.prepareStatement(checkDoubleBookedSQL);
	  addNewReservationStatement = conn.prepareStatement(addNewReservationSQL);
	  getNextRSVIDStatement = conn.prepareStatement(getNextRSVIDSQL);
	  getExistingReservationStatement = conn.prepareStatement(getExistingReservationSQL);
	  getCustomerStatement = conn.prepareStatement(getCustomerSQL);
	  updateCustomerAcctBalanceStatement = conn.prepareStatement(updateCustomerAcctBalanceSQL);
	  setReservationPaidStatement = conn.prepareStatement(setReservationPaidSQL);
	  getCustomersReservationsStatement = conn.prepareStatement(getCustomerReservationsSQL);
	  getFlightByFIDStatement = conn.prepareStatement(getFlightByFIDSQL);
	  deleteReservationStatement = conn.prepareStatement(deleteReservationSQL);
	  getFlightSeatsSoldStatement = conn.prepareStatement(getFlightSeatsSoldSQL);
	  updateSeatsSoldStatement = conn.prepareStatement(updateSeatsSoldSQL);
	  clearCustomersTableDataStatement = conn.prepareStatement("DELETE \n FROM Customers;");
	  clearSeatsSoldTableDataStatement = conn.prepareStatement("DELETE \n FROM SeatsSold;");
	  clearReservationsTableDataStatement = conn.prepareStatement("DELETE \n FROM Reservations;");
	  addNewCustomerStatement = conn.prepareStatement(CREATE_USER);
	  
	  conn.setAutoCommit(true);
	  ResultSet rs = getNextRSVIDStatement.executeQuery();
	  conn.setAutoCommit(false);
	  if (rs.next()) {
		  nextRSVID = rs.getInt("rsvid");
		  // increment for next reservation
		  nextRSVID++;
	  } 
	  else {
		  nextRSVID = 1;
	  }
	  customersLoggedIn = new ArrayList<String>();
	  numItin = 0;
  }

  /**
   * Takes a user's username and password and attempts to log the user in.
   *
   * @param username user's username
   * @param password user's password
   *
   * @return If someone has already logged in, then return "User already logged
   *         in\n" For all other errors, return "Login failed\n". Otherwise,
   *         return "Logged in as [username]\n".
 * @throws SQLException 
   */
  public String transaction_login(String username, String password) {
	  String existingLogIn = "User already logged in\n";
	  String failed = "Login failed\n";
		
	  // logged in?
	  if (!customersLoggedIn.isEmpty()) {
		  return existingLogIn;
	  }
	  try {
		  conn.setAutoCommit(true);
		  ResultSet customer = getCustomer(username);
		  conn.setAutoCommit(false);
		  // username exist?
		  if (customer.next() == false) {
			  return failed;
		  }
		  // compare passwords
		  byte[] customerSalt = customer.getBytes("passwordsalt");
		  String customerPass = customer.getString("passwordhash");
		  if (!checkPassword(password, customerPass, customerSalt)) {
			  return failed;
		  } 
		  else {
			  // log user in, create itineraries - new method?
			  this.itineraries = new ArrayList<Itinerary>();
			  customersLoggedIn.add(username);
			  String r = String.format("Logged in as %s\n", username);
			  return r;
		  }
	  }
	  catch (SQLException | NoSuchAlgorithmException | InvalidKeySpecException e) {
		  // e.printStackTrace();
		  return failed;
	  }
  }

  /**
   * Implement the create user function.
   *
   * @param username   new user's username. User names are unique the system.
   * @param password   new user's password.
   * @param initAmount initial amount to deposit into the user's account, should
   *                   be >= 0 (failure otherwise).
   *
   * @return either "Created user {@code username}\n" or "Failed to create user\n"
   *         if failed. 
   */
  public String transaction_createCustomer(String username, String password, int initAmount) {
	  // Strings to store the hashed password and the salt
	  String hashedPass;
	  String failed = "Failed to create user\n";
	  if (initAmount < 0) {
		  return failed;
	  }
	  // check for customer existence
	  try {
		  // returns search results with possible rows
		  //conn.execute("BEGIN TRANSACTION;");
		  if (getCustomer(username).next() != false) {
			  return failed;
		  }

		  // Generate a random cryptographic salt
		  SecureRandom random = new SecureRandom();
		  byte[] salt = new byte[16];
		  random.nextBytes(salt);
		  hashedPass = encryptPassword(salt, password);
		  // insert customer into the database
		  conn.setAutoCommit(true);
		  addNewCustomerStatement.clearParameters();
		  addNewCustomerStatement.setString(1, username);
		  addNewCustomerStatement.setString(2, hashedPass);
		  addNewCustomerStatement.setBytes(3, salt);
		  addNewCustomerStatement.setInt(4, initAmount);
		  addNewCustomerStatement.executeUpdate();
		  String r = String.format("Created user %s\n", username);
		  conn.setAutoCommit(false);
		  return r;
	  }
	  catch (SQLException | NoSuchAlgorithmException | InvalidKeySpecException e) {
		  return failed;
	  }
  }
  
  private ResultSet getCustomer(String username) throws SQLException {
		// check username existence
	  checkUserStatement.clearParameters();
	  checkUserStatement.setString(1, username);
	  ResultSet alreadyExists = checkUserStatement.executeQuery();
	  return alreadyExists;
  }
  
  private String encryptPassword(byte[] salt, String password) throws NoSuchAlgorithmException, InvalidKeySpecException {
	  // Specify the hash parameters
	  KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_STRENGTH, KEY_LENGTH);
	  // Generate the hash
	  SecretKeyFactory factory = null;
	  byte[] hash = null;
	  factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
	  hash = factory.generateSecret(spec).getEncoded();
	  // convert to String
	  return Optional.of(Base64.getEncoder().encodeToString(hash)).get();
	}
  
  private boolean checkPassword(String inputPassword, String storedPassword, byte[] salt) throws NoSuchAlgorithmException, InvalidKeySpecException {
	  // first we need to hash the input
	  String hashedInput = encryptPassword(salt, inputPassword);
	  return hashedInput.equals(storedPassword);
  }

  /**
   * Implement the search function.
   *
   * Searches for flights from the given origin city to the given destination
   * city, on the given day of the month. If {@code directFlight} is true, it only
   * searches for direct flights, otherwise is searches for direct flights and
   * flights with two "hops." Only searches for up to the number of itineraries
   * given by {@code numberOfItineraries}.
   *
   * The results are sorted based on total flight time.
   *
   * @param originCity
   * @param destinationCity
   * @param directFlight        if true, then only search for direct flights,
   *                            otherwise include indirect flights as well
   * @param dayOfMonth
   * @param numberOfItineraries number of itineraries to return
   *
   * @return If no itineraries were found, return "No flights match your
   *         selection\n". If an error occurs, then return "Failed to search\n".
   *
   *         Otherwise, the sorted itineraries printed in the following format:
   *
   *         Itinerary [itinerary number]: [number of flights] flight(s), [total
   *         flight time] minutes\n [first flight in itinerary]\n ... [last flight
   *         in itinerary]\n
   *
   *         Each flight should be printed using the same format as in the
   *         {@code Flight} class. Itinerary numbers in each search should always
   *         start from 0 and increase by 1.
   *
   * @see Flight#toString()
   */
  public String transaction_search(String originCity, String destinationCity, boolean directFlight, int dayOfMonth,
		  int numberOfItineraries) {
	  String noMatch = "No flights match your selection\n";
	  String failed = "Failed to search\n";
	  this.itineraries = new ArrayList<Itinerary>();
	  StringBuffer sb = new StringBuffer();
	  // reset numItin count
	  this.numItin = 0;
	  try {
		  //conn.execute("BEGIN TRANSACTION;");
		  searchOneHopStatement.clearParameters();
		  searchOneHopStatement.setInt(1, numberOfItineraries);
		  searchOneHopStatement.setString(2, originCity);
		  searchOneHopStatement.setString(3, destinationCity);
		  searchOneHopStatement.setInt(4, dayOfMonth);
		  this.searchResults = searchOneHopStatement.executeQuery();
		  // if there's no results check if indirect flights allowed, otherwise no match
		  if (!searchResults.next()) {
			  // check indirect
			  if (!directFlight) {
				  // search indirect
				  this.searchResults = executeIndirectFlightQuery(originCity, destinationCity, directFlight,
						  dayOfMonth, numberOfItineraries);
				  if (this.searchResults.next()) {
					  // process results
					  processSearchResults(directFlight);
					  printItineraries(numberOfItineraries, sb);
					  //conn.execute("COMMIT;");
					  //conn.commit();
				  }
				  else {
					  sb.append(noMatch);
				  }
			  }
			  else {
				  sb.append(noMatch);
			  }
		  }
		  else {
			  // lets process current results -> set direct true in this case
			  processSearchResults(true);
			  // printItineraries(sb);
			  // check indirect for any flights more time-efficient
			  // get appropriate number here
			  if (!directFlight && (this.numItin < numberOfItineraries)) {
				  int i = (numberOfItineraries - this.numItin);
				  this.searchResults = executeIndirectFlightQuery(originCity, destinationCity, directFlight,
						  dayOfMonth, i);
				  if (this.searchResults.next()) {
					  // process/print
					  processSearchResults(directFlight);
				  }
				  //conn.execute("COMMIT;");
				  //conn.commit();
			  }
			  sortItineraries();
			  numericallyLabelItineraries();
			  printItineraries(numberOfItineraries, sb);
		  }
		  searchResults.close();
	  }
	  catch (SQLException e) {
		  return failed;
	  }
	  return sb.toString();
  }

  private void numericallyLabelItineraries() {
	  int i = 0;
	  for (Itinerary itinerary : this.itineraries) {
		  itinerary.itinNum = i;
		  i++;
	  }
  }
	
  private void sortItineraries() {
	  Collections.sort(this.itineraries, (i1, i2) -> i1.totalFlightTime - i2.totalFlightTime);
  }
  
  private ResultSet executeIndirectFlightQuery(String originCity, String destinationCity, boolean directFlight,
		  int dayOfMonth, int numberOfItineraries) throws SQLException {
	  searchTwoHopStatement.clearParameters();
	  searchTwoHopStatement.setString(1, originCity);
	  searchTwoHopStatement.setInt(2, dayOfMonth);
	  searchTwoHopStatement.setInt(3, numberOfItineraries);
	  searchTwoHopStatement.setString(4, destinationCity);
	  searchTwoHopStatement.setInt(5, dayOfMonth);
	  ResultSet rs = searchTwoHopStatement.executeQuery();
	  return rs;
  }
  
  private void processSearchResults(boolean directFlight) throws SQLException {
	  ArrayList<Flight> flightList;
	  int iteration = this.numItin;
	  do {
		  if (directFlight) {
			  Flight flight = createDirectFlight(searchResults);
			  flightList = new ArrayList<Flight>();
			  flightList.add(flight);
		  }
		  else {
			  flightList = createIndirectFlight();
		  }
		  int flightTime = 0;
		  for (Flight f : flightList) {
			  flightTime += f.time;
		  }
		  Itinerary itin = new Itinerary(iteration, flightList, flightTime);
		  this.itineraries.add(itin);
		  // increase for the next itinerary
		  iteration++;
	  }
	  while (searchResults.next());
	  // update numItin
	  this.numItin = iteration;
  }
  
  private void printItineraries(int numberOfItineraries, StringBuffer sb) {
	  // make sure we have enough itins
	  int num;
	  if (numberOfItineraries > this.itineraries.size()) {
		  num = this.itineraries.size();
	  } else {
		  num = numberOfItineraries;
	  }

	  for (int i = 0; i < num; i++) {
		  Itinerary itin = this.itineraries.get(i);
		  sb.append("Itinerary " + itin.itinNum + ": " + itin.flights.size() + " flight(s), " + itin.totalFlightTime + 
				  " minutes\n");
		  // get every flight
		  for (Flight flight : itin.flights) {
			  sb.append(flight.toString() + "\n");
		  }
	  }
  }
  
  private ArrayList<Flight> createIndirectFlight() throws SQLException {
	  ArrayList<Flight> rArray = new ArrayList<Flight>();

	  // huge list of the flights attributes - not for the faint hearted
	  int result_dayOfMonth = searchResults.getInt("day_of_month");
	  String result_carrierId = searchResults.getString("carrier_id");
	  String result_second_carrierId = searchResults.getString("second_carrier_id");
	  int result_fid = searchResults.getInt("fid");
	  int result_second_fid = searchResults.getInt("second_fid");
	  String result_flightNum = searchResults.getString("flight_num");
	  String result_second_flightNum = searchResults.getString("second_flight_num");
	  String result_originCity = searchResults.getString("origin_city");
	  String result_destCity = searchResults.getString("dest_city");
	  String result_indirectCity = searchResults.getString("indirect_city");
	  int result_time = searchResults.getInt("first_actual_time");
	  int result_second_time = searchResults.getInt("second_actual_time");
	  int result_capacity = searchResults.getInt("capacity");
	  int result_second_capacity = searchResults.getInt("second_capacity");
	  int result_price = searchResults.getInt("price");
	  int result_second_price = searchResults.getInt("second_price");

	  Flight firstFlight = new FlightBuilder().setDayOfMonth(result_dayOfMonth).setCarrierId(result_carrierId)
			  .setFlightNum(result_flightNum).setOriginCity(result_originCity).setDestCity(result_indirectCity)
			  .setTime(result_time).setCapacity(result_capacity).setPrice(result_price).setFid(result_fid).build();
	  Flight secondFlight = new FlightBuilder().setDayOfMonth(result_dayOfMonth).setCarrierId(result_second_carrierId)
			  .setFlightNum(result_second_flightNum).setOriginCity(result_indirectCity).setDestCity(result_destCity)
			  .setTime(result_second_time).setCapacity(result_second_capacity).setPrice(result_second_price)
			  .setFid(result_second_fid).build();
	  rArray.add(firstFlight);
	  rArray.add(secondFlight);
	  return rArray;
	}
  
  private Flight createDirectFlight(ResultSet searchResults) throws SQLException {

	  int result_dayOfMonth = searchResults.getInt("day_of_month");
	  int result_fid = searchResults.getInt("fid");
	  String result_carrierId = searchResults.getString("carrier_id");
	  String result_flightNum = searchResults.getString("flight_num");
	  String result_originCity = searchResults.getString("origin_city");
	  String result_destCity = searchResults.getString("dest_city");
	  int result_time = searchResults.getInt("actual_time");
	  int result_capacity = searchResults.getInt("capacity");
	  int result_price = searchResults.getInt("price");
	  // create a flight
	  return new FlightBuilder().setDayOfMonth(result_dayOfMonth).setCarrierId(result_carrierId)
			  .setFlightNum(result_flightNum).setOriginCity(result_originCity).setDestCity(result_destCity)
			  .setTime(result_time).setCapacity(result_capacity).setPrice(result_price).setFid(result_fid).build();
	}

  /**
   * Implements the book itinerary function.
   *
   * @param itineraryId ID of the itinerary to book. This must be one that is
   *                    returned by search in the current session.
   *
   * @return If the user is not logged in, then return "Cannot book reservations,
   *         not logged in\n". If try to book an itinerary with invalid ID, then
   *         return "No such itinerary {@code itineraryId}\n". If the user already
   *         has a reservation on the same day as the one that they are trying to
   *         book now, then return "You cannot book two flights in the same
   *         day\n". For all other errors, return "Booking failed\n".
   *
   *         And if booking succeeded, return "Booked flight(s), reservation ID:
   *         [reservationId]\n" where reservationId is a unique number in the
   *         reservation system that starts from 1 and increments by 1 each time a
   *         successful reservation is made by any user in the system.
   */
  public String transaction_book(int itineraryId) {
	  String notLoggedIn = "Cannot book reservations, not logged in\n";
	  String failed = "Booking failed\n";

	  if (!this.somebodyLoggedIn()) {
		  return notLoggedIn;
	  } 
	  else {
		  try {
			  conn.setAutoCommit(false);
			  conn.createStatement().execute("BEGIN TRANSACTION;");
			  // Inv for every itinerary i looked at:
			  // itineraryId != i.itinNum
			  for (Itinerary itinerary : this.itineraries) {
				  if (itinerary.itinNum == itineraryId) {
					  // check if client already traveling that day
					  int dayOfTravel = itinerary.flights.get(0).dayOfMonth;
					  String userName = this.customersLoggedIn.get(0);
					  if (priorTravelPlans(userName, dayOfTravel)) {
						  //ResultSet test1 = testStatement.executeQuery();
						  //System.out.print(test1.next());
						  String alreadyTraveling = "You cannot book two flights in the same day\n";
						  conn.createStatement().execute("ROLLBACK;");
						  conn.setAutoCommit(true);
						  return alreadyTraveling;
					  }
					  else {
						  boolean enoughSeats = checkCapacity(itinerary);
						  if (enoughSeats) {
							  bookReservation(userName, dayOfTravel, itinerary);
							  for (Flight flight : itinerary.flights) {
								  updateSeatsSold(flight.fid, 1);
							  }
							  String s = "Booked flight(s), reservation ID: " + nextRSVID + "\n";
							  nextRSVID++;
							  conn.createStatement().execute("COMMIT;");
							  conn.setAutoCommit(true);
							  return (s);
						  } 
						  else {
							  conn.createStatement().execute("ROLLBACK;");
							  conn.setAutoCommit(true);
							  return failed;
						  }
					  }
				  }
			  }
		  }
		  catch(SQLException e) {
			  return transaction_book(itineraryId);
			  //String noItin = "No such itinerary " + itineraryId + "\n";
			  //return noItin;
		  }
	  }
	  return "WE SHOULDN'T BE HERE!!!!";
  }


  
  private void updateSeatsSold(int fid, int seats) throws SQLException {
	  updateSeatsSoldStatement.clearParameters();
	  updateSeatsSoldStatement.setInt(1, seats);
	  updateSeatsSoldStatement.setInt(2, fid);
	  updateSeatsSoldStatement.setInt(3, fid);
	  updateSeatsSoldStatement.setInt(4, seats);
	  updateSeatsSoldStatement.executeUpdate();
	}
  
  private boolean checkCapacity(Itinerary itinerary) throws SQLException {
		for (Flight flight : itinerary.flights) {
			int capacity = flight.capacity;
			int seatsTaken = flightSeatsSold(flight.fid);
			if (capacity <= seatsTaken) {
				return false;
			}
		}
		return true;
	}
  
  private int flightSeatsSold(int fid) throws SQLException {
		int sold = 0;
		getFlightSeatsSoldStatement.setInt(1, fid);
		ResultSet seatsSold = getFlightSeatsSoldStatement.executeQuery();
		if (seatsSold.next()) {
			sold = seatsSold.getInt("seatsSold");
		}
//		seatsSold.close();
		return sold;
	}
  
  private void bookReservation(String customer, int day, Itinerary itinerary) throws SQLException {
		int ogFID, indirFID;
		Flight indirFlight;
		// get cost
		int totalPrice = 0;
		indirFID = 0;

		// really ugly, should be able to generalize this
		Flight flight = itinerary.flights.get(0);
		totalPrice += flight.price;
		ogFID = flight.fid;
		String s = String.format("ogFID %d", ogFID);
		// System.out.println(s);
		// not a direct flight
		if (itinerary.flights.size() > 1) {
			indirFlight = itinerary.flights.get(1);
			totalPrice += indirFlight.price;
			indirFID = indirFlight.fid;
			s = String.format("ogFID %d", indirFID);
			// System.out.println(s);
		}
		addNewReservationStatement.clearParameters();
		addNewReservationStatement.setInt(1, nextRSVID);
		addNewReservationStatement.setString(2, customer);
		addNewReservationStatement.setInt(3, totalPrice);
		addNewReservationStatement.setInt(4, ogFID);
		addNewReservationStatement.setInt(5, indirFID);
		addNewReservationStatement.setInt(6, day);
		addNewReservationStatement.setInt(7, 0);
		addNewReservationStatement.executeUpdate();
	}
  
  private boolean priorTravelPlans(String customer, int dayOfTravel) throws SQLException {
		boolean bool = false;
		// check that table of ours
		//conn.setAutoCommit(true);
		checkDoubleBookStatement.clearParameters();
		checkDoubleBookStatement.setString(1, customer);
		checkDoubleBookStatement.setInt(2, dayOfTravel);
		ResultSet rs = checkDoubleBookStatement.executeQuery();
		//conn.setAutoCommit(false);
		if (rs.next()) {
			bool = true;
		}
		// rs.close();
		return bool;
	}

  /**
   * Implements the pay function.
   *
   * @param reservationId the reservation to pay for.
   *
   * @return If no user has logged in, then return "Cannot pay, not logged in\n"
   *         If the reservation is not found / not under the logged in user's
   *         name, then return "Cannot find unpaid reservation [reservationId]
   *         under user: [username]\n" If the user does not have enough money in
   *         their account, then return "User has only [balance] in account but
   *         itinerary costs [cost]\n" For all other errors, return "Failed to pay
   *         for reservation [reservationId]\n"
   *
   *         If successful, return "Paid reservation: [reservationId] remaining
   *         balance: [balance]\n" where [balance] is the remaining balance in the
   *         user's account.
   */
  public String transaction_pay(int reservationId) {
	  String failed = "Failed to pay for reservation " + reservationId + "\n";
		String userName;

		// if nobody logged in
		if (!this.somebodyLoggedIn()) {
			String s = "Cannot pay, not logged in\n";
			return s;
			}
		userName = this.customersLoggedIn.get(0);
		String cantFindRes = "Cannot find unpaid reservation " + reservationId + " under user: " + userName + "\n";
		try {
			// try and find the reservation
			conn.setAutoCommit(false);
			conn.createStatement().execute("BEGIN TRANSACTION;");
			getExistingReservationStatement.setInt(1, reservationId);
			ResultSet reservation = getExistingReservationStatement.executeQuery();
			if (reservation.next()) {
				// already paid?
				if (reservation.getInt("paid") == 1) {
					conn.createStatement().execute("ROLLBACK;");
					conn.setAutoCommit(true);
					return cantFindRes;
				}
				String resName = reservation.getString("customer");
				// make sure this reservation belongs to the user
				if (resName.equals(userName)) {
					getCustomerStatement.setString(1, resName);
					ResultSet customer = getCustomerStatement.executeQuery();
					if (customer.next()) {
						// check if funds available
						int balance = customer.getInt("acctBalance");
						int cost = reservation.getInt("totprice");
						if (balance > cost) {
							// adjust customer balance, update info
							balance -= cost;
							updateCustomerBalance(userName, balance);
							updateReservationPaid(reservationId);
							String r = String.format("Paid reservation: %d remaining balance: %d\n", reservationId,
									balance);
							conn.createStatement().execute("COMMIT;");
							conn.setAutoCommit(true);
							return r;
						} else {
							String s = "User has only " + balance + " in account but itinerary costs " + cost + "\n";
							conn.createStatement().execute("ROLLBACK;");
							conn.setAutoCommit(true);
							return s;
						}
					}
				} else {
					conn.createStatement().execute("ROLLBACK;");
					conn.setAutoCommit(true);
					return cantFindRes;
				}
			} else {
				conn.createStatement().execute("ROLLBACK;");
				conn.setAutoCommit(true);
				return cantFindRes;
			}
		} catch (SQLException e) {
			try {
				conn.createStatement().execute("ROLLBACK;");
				conn.setAutoCommit(true);
				return failed;
			}
			catch(SQLException ex) {
			}
		}
		return failed;
	}
  
  private void updateReservationPaid(int reservationId) throws SQLException {
	  setReservationPaidStatement.clearParameters();
	  setReservationPaidStatement.setInt(1, reservationId);
	  setReservationPaidStatement.executeUpdate();
  }
  
  private void updateCustomerBalance(String userName, int balance) throws SQLException {
	  updateCustomerAcctBalanceStatement.clearParameters();
	  updateCustomerAcctBalanceStatement.setInt(1, balance);
	  updateCustomerAcctBalanceStatement.setString(2, userName);
	  updateCustomerAcctBalanceStatement.executeUpdate();
  }

  /**
   * Implements the reservations function.
   *
   * @return If no user has logged in, then return "Cannot view reservations, not
   *         logged in\n" If the user has no reservations, then return "No
   *         reservations found\n" For all other errors, return "Failed to
   *         retrieve reservations\n"
   *
   *         Otherwise return the reservations in the following format:
   *
   *         Reservation [reservation ID] paid: [true or false]:\n" [flight 1
   *         under the reservation] [flight 2 under the reservation] Reservation
   *         [reservation ID] paid: [true or false]:\n" [flight 1 under the
   *         reservation] [flight 2 under the reservation] ...
   *
   *         Each flight should be printed using the same format as in the
   *         {@code Flight} class.
   *
   * @see Flight#toString()
   */
  public String transaction_reservations() {
	  String failed = "Failed to retrieve reservations\n";
	  if (!this.somebodyLoggedIn()) {
		  String s = String.format("Cannot view reservations, not logged in\n");
		  return s;
	  }
	  String userName = this.customersLoggedIn.get(0);
	  try {
		  this.reservationResults = getUserReservations(userName);
		  if (reservationResults.next()) {
			  StringBuffer sb = new StringBuffer();
			  processReservationResults(sb);
			  return sb.toString();
		  }
		  // this.reservationResults.close();
	  }
	  catch (SQLException e) {
		  return failed;
	  }
	return failed;
}
  
  private ResultSet getUserReservations(String userName) throws SQLException {
	  getCustomersReservationsStatement.clearParameters();
	  getCustomersReservationsStatement.setString(1, userName);
	  ResultSet rs = getCustomersReservationsStatement.executeQuery();
	  return rs;
  }
  
  private void processReservationResults(StringBuffer sb) throws SQLException {
		// Inv - for every reservation seen so far, r:
		// we have appended the id, paid boolean, and flight information to sb
		do {
			int rsvID = reservationResults.getInt("rsvid");
			int paidInt = reservationResults.getByte("paid");
			boolean paid = (paidInt == 1) ? true : false;
			String s = String.format("Reservation %d paid: %b:\n", rsvID, paid);
			sb.append(s);
			// now for the flights
			int originFID = reservationResults.getInt("origin_city_fid");
			int indirectFID = reservationResults.getInt("indirect_oc_fid");
			Flight flight = getFlight(originFID);
			sb.append(flight.toString() + "\n");
			if (indirectFID != 0) {
				flight = getFlight(indirectFID);
				sb.append(flight.toString() + "\n");
			}
		} while (reservationResults.next());
//		reservationResults.close();
	}
  
  private Flight getFlight(int originFID) throws SQLException {
	  getFlightByFIDStatement.setInt(1, originFID);
	  userFlight = getFlightByFIDStatement.executeQuery();
	  // increment
	  userFlight.next();
	  return createDirectFlight(userFlight);
	}
  
  private void deleteReservation(int reservationId) throws SQLException {
	  deleteReservationStatement.clearParameters();
	  deleteReservationStatement.setInt(1, reservationId);
	  deleteReservationStatement.executeUpdate();
  }
  
  private void refundCustomer(String userName, int refundAmount) throws SQLException {
		// need to get customers bank info
	  getCustomerStatement.setString(1, userName);
	  ResultSet customer = getCustomerStatement.executeQuery();
	  customer.next();
	  int custBal = customer.getInt("acctBalance");
	  // calculate new total and update
	  custBal += refundAmount;
	  updateCustomerBalance(userName, custBal);
  }
  
  private boolean somebodyLoggedIn() {
		if (this.customersLoggedIn.isEmpty()) {
			return false;
		}
		return true;
	}

  /**
   * Implements the cancel operation.
   *
   * @param reservationId the reservation ID to cancel
   *
   * @return If no user has logged in, then return "Cannot cancel reservations,
   *         not logged in\n" For all other errors, return "Failed to cancel
   *         reservation [reservationId]\n"
   *
   *         If successful, return "Canceled reservation [reservationId]\n"
   *
   *         Even though a reservation has been canceled, its ID should not be
   *         reused by the system.
   */
  public String transaction_cancel(int reservationId) {
	  int reservID = reservationId;
	  String failed = String.format("Failed to cancel reservation %d\n", reservationId);
	  String userName;
	  if (!this.somebodyLoggedIn()) {
		  String s = "Cannot cancel reservations, not logged in\n";
		  return s;
	  }
	  userName = this.customersLoggedIn.get(0);
	  try {
		  //conn.execute("BEGIN TRANSACTION;");
		  getExistingReservationStatement.setInt(1, reservationId);
		  this.reservationResults = getExistingReservationStatement.executeQuery();
		  if (this.reservationResults.next()) {
			  // make sure this reservation belongs to this user
			  String resUserName = this.reservationResults.getString("customer");
			  if (userName.equals(resUserName)) {
				  // cancel this thing
				  // need to refund and remove row
				  int paid = reservationResults.getByte("paid");
				  if (paid == 1) {
					  int refundAmount = reservationResults.getInt("totprice");
					  this.refundCustomer(userName, refundAmount);
				  }
				  this.deleteReservation(reservationId);
				  int originFID = reservationResults.getInt("origin_city_fid");
				  int indirectFID = reservationResults.getInt("indirect_oc_fid");
				  updateSeatsSold(originFID, -1);
				  if (indirectFID != 0) {
					  updateSeatsSold(indirectFID, -1);
				  }
				  String r = String.format("Canceled reservation %d\n", reservationId);
				  //conn.execute("COMMIT;");
				  //conn.commit();
				  return r;
			  }
		  }
	  } 
	  catch (SQLException e) {
			  return failed;
	  }
	  return failed;
	}
  
  public class Itinerary {
		private int itinNum;
		private ArrayList<Flight> flights;
		private int totalFlightTime;

		public Itinerary(int itinNum, ArrayList<Flight> flights, int totalFlightTime) {
			this.itinNum = itinNum;
			this.flights = flights;
			this.totalFlightTime = totalFlightTime;
		}
	}
  
  public class FlightBuilder {
		private int fid;
		private int dayOfMonth;
		private String carrierId;
		private String flightNum;
		private String originCity;
		private String destCity;
		private int time;
		private int capacity;
		private int price;

		public FlightBuilder() {
		}

		public FlightBuilder setFid(int fid) {
			this.fid = fid;
			return this;
		}

		public FlightBuilder setDayOfMonth(int dayOfMonth) {
			this.dayOfMonth = dayOfMonth;
			return this;
		}

		public FlightBuilder setCarrierId(String carrierId) {
			this.carrierId = carrierId;
			return this;
		}

		public FlightBuilder setFlightNum(String flightNum) {
			this.flightNum = flightNum;
			return this;
		}

		public FlightBuilder setOriginCity(String originCity) {
			this.originCity = originCity;
			return this;
		}

		public FlightBuilder setDestCity(String destCity) {
			this.destCity = destCity;
			return this;
		}

		public FlightBuilder setTime(int time) {
			this.time = time;
			return this;
		}

		public FlightBuilder setCapacity(int capacity) {
			this.capacity = capacity;
			return this;
		}

		public FlightBuilder setPrice(int price) {
			this.price = price;
			return this;
		}

		public Flight build() {
			return new Flight(this);
		}
	}

  /**
   * A class to store flight information.
   */
  private class Flight {
		public int fid;
		public int dayOfMonth;
		public String carrierId;
		public String flightNum;
		public String originCity;
		public String destCity;
		public int time;
		public int capacity;
		public int price;

		private Flight(FlightBuilder fb) {
			this.fid = fb.fid;
			this.dayOfMonth = fb.dayOfMonth;
			this.carrierId = fb.carrierId;
			this.flightNum = fb.flightNum;
			this.originCity = fb.originCity;
			this.destCity = fb.destCity;
			this.time = fb.time;
			this.capacity = fb.capacity;
			this.price = fb.price;
		}

		@Override
		public String toString() {
			return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: " + flightNum
					+ " Origin: " + originCity + " Dest: " + destCity + " Duration: " + time + " Capacity: " + capacity
					+ " Price: " + price;
		}
	}
}
