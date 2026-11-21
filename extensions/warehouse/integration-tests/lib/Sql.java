// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/** Runs each argument after host/port as one statement on a single connection and prints the result as TSV. */
public class Sql {
    public static void main(String[] args) throws Exception {
        String host = args[0];
        String port = args[1];
        int waitSeconds = Integer.parseInt(System.getProperty("wait", "0"));
        String url = "jdbc:mariadb://" + host + ":" + port + "/?user=" + System.getProperty("user", "root") + "&connectTimeout=3000";
        Connection conn = null;
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (true) {
            try {
                conn = DriverManager.getConnection(url);
                break;
            } catch (SQLException e) {
                if (System.currentTimeMillis() >= deadline) {
                    System.out.println("CONNECT FAILED: " + e.getMessage());
                    System.exit(2);
                }
                Thread.sleep(1000);
            }
        }
        try (Connection c = conn; Statement st = c.createStatement()) {
            for (int i = 2; i < args.length; i++) {
                System.out.println("mysql> " + args[i]);
                try {
                    boolean hasRs = st.execute(args[i]);
                    if (!hasRs) {
                        System.out.println("OK");
                        continue;
                    }
                    try (ResultSet rs = st.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        StringBuilder sb = new StringBuilder();
                        for (int col = 1; col <= md.getColumnCount(); col++) {
                            sb.append(col > 1 ? "\t" : "").append(md.getColumnLabel(col));
                        }
                        System.out.println(sb);
                        int rows = 0;
                        while (rs.next()) {
                            sb.setLength(0);
                            for (int col = 1; col <= md.getColumnCount(); col++) {
                                sb.append(col > 1 ? "\t" : "").append(rs.getString(col));
                            }
                            System.out.println(sb);
                            rows++;
                        }
                        System.out.println("(" + rows + " rows)");
                    }
                } catch (SQLException e) {
                    System.out.println("ERROR " + e.getErrorCode() + ": " + e.getMessage());
                }
            }
        }
    }
}
