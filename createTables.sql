CREATE TABLE Customers( 
	username VARCHAR (100) PRIMARY KEY NOT NULL, 
	passwordhash VARCHAR(MAX) NOT NULL, 
	passwordsalt BINARY(16) NOT NULL, 
	acctBalance INT NOT NULL); 
	
CREATE TABLE Reservations( 
	rsvid INT PRIMARY KEY NOT NULL, 
	customer VARCHAR(100) NOT NULL,	
	totprice INT NOT NULL, 
	origin_city_fid INT NOT NULL, 
	indirect_oc_fid INT, 
	date INT NOT NULL, 
	paid BIT NOT NULL); 
	
CREATE TABLE SeatsSold(
	fid INT PRIMARY KEY NOT NULL, 
	seatsSold INT NOT NULL); 