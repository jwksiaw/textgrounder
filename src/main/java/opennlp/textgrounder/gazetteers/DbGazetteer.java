///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2010 Travis Brown, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////
package opennlp.textgrounder.gazetteers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;

import opennlp.textgrounder.topostructs.Coordinate;
import opennlp.textgrounder.topostructs.Location;

public class DbGazetteer extends Gazetteer {
  protected final Connection connection;
  protected PreparedStatement insertStatement;
  protected PreparedStatement lookupStatement;
  protected int inBatch;
  protected final int batchSize;

  public static Gazetteer create(String url) {
    Gazetteer result = null;
    try {
      result = new DbGazetteer(DriverManager.getConnection(url));
    } catch (SQLException e) {
      System.err.format("Could not create database connection: %s\n", e);
      e.printStackTrace();
      System.exit(1);
    }
    return result;
  }

  public DbGazetteer(Connection connection) {
    this(connection, 1024 * 512);
  }

  public DbGazetteer(Connection connection, int batchSize) {
    this.inBatch = 0;
    this.batchSize = batchSize;
    this.connection = connection;
  }

  public void add(String name, Location location) {
    Coordinate coordinate = location.getCoord();
    try {
      PreparedStatement statement = this.getInsertStatement();
      statement.setInt(1, location.getId());
      statement.setString(2, name);
      statement.setString(3, location.getType());
      statement.setDouble(4, coordinate.latitude);
      statement.setDouble(5, coordinate.longitude);
      statement.setInt(6, location.getPop());
      statement.setString(7, location.getContainer());
      statement.addBatch();

      if (this.inBatch == this.batchSize) {        
        this.inBatch = 0;
        this.insertStatement.executeBatch();
        this.insertStatement.close();
      }

    } catch (SQLException e) {
      System.err.format("Error while adding location to database: %s\n", e);
      e.printStackTrace();
      System.exit(1); 
    }
  }

  public List<Location> lookup(String query) {
    ArrayList<Location> locations = new ArrayList<Location>();

    try {
      PreparedStatement statement = this.getLookupStatement();
      statement.setString(1, query);
      ResultSet result = statement.executeQuery();
      while (result.next()) {
        int id = result.getInt(1);
        String name = result.getString(2);
        String type = result.getString(3);
        double lat = result.getDouble(4);
        double lng = result.getDouble(5);
        int population = result.getInt(6);
        String container = result.getString(7);
        Coordinate coordinate = new Coordinate(lng, lat);
        locations.add(new Location(id, name, type, coordinate, population, container));
      }
      result.close();
      locations.trimToSize();
    } catch (SQLException e) {
      System.err.format("Error: could not perform database lookup: %s\n", e);
      e.printStackTrace();
    }

    return locations;
  }

  @Override
  public void close() {
    try {
      this.connection.close();
    } catch (SQLException e) {
      System.err.format("Could not close database connection: %s\n", e);
      e.printStackTrace();
    }
  }

  protected void createSchema() throws SQLException {
    StringBuilder builder = new StringBuilder();
    builder.append("CREATE TABLE places (");
    builder.append("  id INTEGER PRIMARY KEY,");
    builder.append("  name VARCHAR(256),");
    builder.append("  type VARCHAR(256),");
    builder.append("  lat DOUBLE,");
    builder.append("  lng DOUBLE,");
    builder.append("  pop INTEGER,");
    builder.append("  container VARCHAR(256))");

    Statement statement = this.connection.createStatement();
    statement.executeUpdate(builder.toString());
    statement.executeUpdate("CREATE INDEX nameIdx ON places (name)");
    statement.close();
  }

  protected PreparedStatement getInsertStatement() throws SQLException {
    if (this.insertStatement == null || this.insertStatement.isClosed()) {
      String query = "INSERT INTO places VALUES (?, ?, ?, ?, ?, ?, ?)";
      this.insertStatement = this.connection.prepareStatement(query);
    }
    return this.insertStatement;
  }

  protected PreparedStatement getLookupStatement() throws SQLException {
    if (this.lookupStatement == null || this.lookupStatement.isClosed()) {
      String query = "SELECT * FROM places WHERE name = ?";
      this.lookupStatement = this.connection.prepareStatement(query);
    }
    return this.lookupStatement;
  }
}
