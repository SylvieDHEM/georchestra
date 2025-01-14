package org.georchestra.analytics.model;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class AbstractModel {

	protected PostGresqlConnection postgresqlConnection;

	private final String countQ = "SELECT count(*) from (@query@) as res;";

	public AbstractModel(PostGresqlConnection pgpool) {
		postgresqlConnection = pgpool;
	}

	/**
	 * Prepares the statement with controller attributes
	 *
	 * @return
	 * @throws SQLException
	 */
	protected PreparedStatement prepareStatement(Connection con, final String query,
			final int month, final int year, final int start, final int limit, final String sort,
			List<String> extraFilters) throws SQLException {

		String q = query.replace("@sort@", sort);
		PreparedStatement st = con.prepareStatement(q);

		// Extra filters come first (replacing the WHERE clause by WHERE ...)
		int curParam = 1;
		for (String extrafilter : extraFilters) {
		    st.setString(curParam++, extrafilter);
		}


        if ((month > 0) && (year > 0)) {
            st.setString(curParam++, String.format("%4d-%02d-01 00:00", year, month));
            if (month < 12)
                st.setString(curParam++,
                        String.format("%4d-%02d-01 00:00", year, month + 1));
            else
                st.setString(curParam++, String.format("%4d-01-01 00:00", year + 1));
        } else {
            // (hackish, same comment as below)
            st.setString(curParam++, "1970-01-01 00:00");
            st.setString(curParam++, "2032-01-01 00:00");
        }
        st.setInt(curParam++, limit);
        st.setInt(curParam++, start);

		return st;
	}

	/**
	 * Counts all the results of the given query.
	 *
	 * Builds the count query from the filter query, removing LIMIT and OFFSET keywords
	 * and includes in a count query named "countQ".
	 *
	 * @return number of results
	 * @throws SQLException
	 */
	protected int getCount(Connection con, final String query,
			final int month, final int year, final String sort, List<String> extraFilters) throws SQLException {

		ResultSet rs = null;
		PreparedStatement st = null;
		int count = 0;
		String q = query.replace("@sort@", sort);
		q = q.replace("LIMIT ? OFFSET ?;", "");
		q = countQ.replace("@query@", q);



		try {
			st = con.prepareStatement(q);

	        int curParam = 1;
	        for (String extrafilter : extraFilters) {
	                st.setString(curParam++, extrafilter);
	        }

            if ((month > 0) && (year > 0)) {
                st.setString(curParam++, String.format("%4d-%02d-01 00:00", year, month));
                if (month < 12) {
                    st.setString(curParam++, String.format("%4d-%02d-01 00:00", year, month + 1));
                } else {
                    st.setString(curParam++, String.format("%4d-01-01 00:00", year + 1));
                }
            } else {
                // hack-ish, but need to find out a better way to do,
                // I've until 2032 to rewrite this in a better fashion.
                st.setString(curParam++, "1970-01-01 00:00");
                st.setString(curParam++, "2032-01-01 00:00");
            }
		    rs = st.executeQuery();
		    if(rs.next()) {
		    	count = rs.getInt(1);
		    }
		} catch(SQLException e) {
			throw e;
		} finally {
			if (st != null) st.close();
			if (rs != null) rs.close();
		}
		return count;
	}

	/**
	 * Generic statistics data access. Gets all statistics of a type, filtered by date, ordered and
	 * sampled (offset, limit). The ResultSet is parsed and all data are inserted in a JSON object
	 * actually returned.
	 * @param filter
	 * @return JSON object containing all results
	 * @throws SQLException
	 * @throws JSONException
	 */
	public JSONObject getStats(final int month, final int year, final int start, final int limit,
			final String sort, String filter, final String query, StrategyModel strategy) throws SQLException, JSONException {

		JSONObject object = new JSONObject();
		ResultSet rs = null;
		Connection con = null;
		PreparedStatement st = null;

		// The current block code corresponds to the deprecated
		// addFilters() method
		String q = new String(query);
		List<String> extraFilters = new ArrayList<String>();

		if ((filter != null) && (! "".equals(filter))) {

	        JSONArray arr = new JSONArray(filter);

	        StringBuilder sb = new StringBuilder();
	        sb.append("WHERE");

	        for (int i=0;  i < arr.length() ; ++i) {
	            JSONObject f = arr.getJSONObject(i);
	            sb.append(" ");
	            sb.append(f.getString("property"));
	            // TODO we should avoid casting if we can predict the type
	            sb.append("::text = ? ");
	            extraFilters.add(f.getString("value"));
	            sb.append(" AND");
	        }
	        sb.append(" ");

	        // Case-sensivity of the where
	        q = q.replace("WHERE", sb.toString());
		}
        // end block


		try {
			//String q = addFilters(query, filter);

			con = postgresqlConnection.getConnection();
			int count = getCount(con, q, month, year, sort, extraFilters);
			st = prepareStatement(con, q, month, year, start, limit, sort, extraFilters);
			rs = st.executeQuery();

			JSONArray jsarr = strategy.process(rs);
			object.put("success", true);
			object.put("results", jsarr);
			object.put("total", count);

			return object;

		} catch (SQLException e) {
			throw e;

		}  catch (JSONException e) {
			throw e;

		} finally {
			if (st != null) st.close();
			if (rs != null) rs.close();

			if (con != null) {
				con.close();
			}
		}
	}

	protected abstract class StrategyModel {

		protected abstract JSONArray process(ResultSet rs) throws SQLException, JSONException;
	}
}