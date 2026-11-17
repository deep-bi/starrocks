// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
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

package com.starrocks.jdbcbridge;

import com.starrocks.connector.share.security.FipsCertificateProvider;
import com.starrocks.connector.share.security.FipsTlsCryptoProvider;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.Provider;
import java.security.Security;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

public class JDBCScannerTest {
    @BeforeAll
    public static void initializeProviders() {
        System.setProperty("org.bouncycastle.fips.approved_only", "true");
        System.setProperty("org.bouncycastle.native.cpu_variant", "java");
        if (Security.getProvider("BCFIPS") == null) {
            Security.addProvider(new FipsCertificateProvider());
            Security.addProvider(new BouncyCastleFipsProvider());
            Security.addProvider(new BouncyCastleJsseProvider("fips:" + FipsTlsCryptoProvider.class.getName()));
        }
    }

    @Test
    public void testCachedResultSetRemainsReadableWhenProvidersAreRemoved() throws Exception {
        JDBCScanner scanner = createScanner("org.postgresql.Driver", "UTC", 1);
        setField(scanner, "resultSetMetaData", singleColumnMetaData());
        setField(scanner, "resultChunk", singleStringColumnChunk());
        setResultColumnClassNames(scanner, List.of("java.lang.String"));
        AtomicInteger row = new AtomicInteger();
        setField(scanner, "resultSet", proxy(ResultSet.class, (method, args) -> {
            if ("next".equals(method.getName())) {
                row.incrementAndGet();
                return true;
            }
            if ("getObject".equals(method.getName())) {
                return "row-" + row.get();
            }
            return defaultValue(method);
        }));
        Assertions.assertTrue(scanner.hasNext());
        Assertions.assertEquals("row-1", scanner.getNextChunk().get(0)[0]);
        int expectedRow = 1;
        for (String name : List.of("BCFIPS", "BCJSSE", "StarRocksPKIX")) {
            Provider provider = Security.getProvider(name);
            Security.removeProvider(name);
            try {
                for (int retry = 0; retry < 3; retry++) {
                    Assertions.assertTrue(scanner.hasNext());
                    Assertions.assertEquals("row-" + ++expectedRow, scanner.getNextChunk().get(0)[0]);
                    Assertions.assertEquals(1, scanner.getResultNumRows());
                }
            } finally {
                Security.addProvider(provider);
            }
        }
        Assertions.assertEquals(expectedRow, row.get());
    }

    @Test
    public void testExternalDriverReceivesUnchangedUrlAndCredentials() throws Exception {
        for (String url : List.of("jdbc:unreviewed://localhost/review?custom=value",
                "jdbc:oracle:thin:@//localhost:1521/review?oracle.net.useJCEAPI=false",
                "jdbc:postgresql://localhost/review?requireAuth=md5&sslmode=prefer",
                "jdbc:mariadb://localhost/review?restrictedAuth=client_ed25519",
                "jdbc:mysql://localhost/review?sslMode=PREFERRED&useConfigs=maxPerformance",
                "jdbc:sqlserver://localhost;integratedSecurity=true",
                "jdbc:clickhouse://localhost/review?sslmode=none")) {
            JDBCScanContext context = new JDBCScanContext(RecordingDriver.class.getName(), url,
                    "review-user", "synthetic-review-password", "SELECT 1", "UTC",
                    1, 1, 0, 0, 1000, 30000, 0);
            String driver = new java.io.File(RecordingDriver.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getPath();
            JDBCScanner scanner = new JDBCScanner(driver, context);
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            RecordingDriver.url = null;
            RecordingDriver.properties = null;
            try {
                var failure = Assertions.assertThrows(com.zaxxer.hikari.pool.HikariPool.PoolInitializationException.class,
                        scanner::open);
                Assertions.assertEquals("Fixture stops before network access", failure.getCause().getMessage());
                Assertions.assertEquals(url, RecordingDriver.url);
                Properties expected = new Properties();
                expected.setProperty("user", "review-user");
                expected.setProperty("password", "synthetic-review-password");
                Assertions.assertEquals(expected, RecordingDriver.properties);
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }
    }

    public static final class RecordingDriver implements Driver {
        private static String url;
        private static Properties properties;

        @Override
        public Connection connect(String jdbcUrl, Properties info) throws SQLException {
            url = jdbcUrl;
            properties = (Properties) info.clone();
            throw new SQLException("Fixture stops before network access");
        }

        @Override
        public boolean acceptsURL(String jdbcUrl) {
            return true;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String jdbcUrl, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }
    }

    @Test
    public void testOracleVarcharColumnDoesNotUseTemporalConversion() throws Exception {
        JDBCScanner scanner = createScanner("oracle.jdbc.driver.OracleDriver", "Asia/Shanghai", 1);
        setResultColumnClassNames(scanner, List.of("java.lang.String"));

        Assertions.assertFalse(invokeShouldConvertOracleTemporalStringColumn(scanner, 0));
    }

    @Test
    public void testPostgresTimeWithTimezoneTypeNameDetected() throws Exception {
        JDBCScanner scanner = createScanner("org.postgresql.Driver", "Asia/Shanghai", 1);
        Assertions.assertTrue(invokeIsPostgresTimeWithTimezoneTypeName(scanner, "timetz"));
        Assertions.assertTrue(invokeIsPostgresTimeWithTimezoneTypeName(scanner, "time with time zone"));
        Assertions.assertFalse(invokeIsPostgresTimeWithTimezoneTypeName(scanner, "timestamptz"));
        Assertions.assertFalse(invokeIsPostgresTimeWithTimezoneTypeName(scanner, "timestamp with time zone"));
        Assertions.assertFalse(invokeIsPostgresTimeWithTimezoneTypeName(scanner, "TIMESTAMPTZ(6)"));
    }

    @Test
    public void testPostgresTemporalWithTimezoneDetectDoesNotAffectOtherDrivers() throws Exception {
        JDBCScanner oracleScanner = createScanner("oracle.jdbc.driver.OracleDriver", "Asia/Shanghai", 1);
        Assertions.assertFalse(invokeIsPostgresTimeWithTimezoneTypeName(oracleScanner, "timetz"));

        JDBCScanner mysqlScanner = createScanner("com.mysql.jdbc.Driver", "Asia/Shanghai", 1);
        Assertions.assertFalse(invokeIsPostgresTimeWithTimezoneTypeName(mysqlScanner, "timestamptz"));
    }

    @Test
    public void testPostgresTimestampWithTimezoneTypeNameDetected() throws Exception {
        JDBCScanner scanner = createScanner("org.postgresql.Driver", "Asia/Shanghai", 1);
        Assertions.assertTrue(invokeIsPostgresTimestampWithTimezoneTypeName(scanner, "timestamptz"));
        Assertions.assertTrue(invokeIsPostgresTimestampWithTimezoneTypeName(scanner, "timestamp with time zone"));
        Assertions.assertTrue(invokeIsPostgresTimestampWithTimezoneTypeName(scanner, "TIMESTAMPTZ(6)"));
        Assertions.assertFalse(invokeIsPostgresTimestampWithTimezoneTypeName(scanner, "timestamp"));
    }

    @Test
    public void testOracleTimestamptzColumnConvertsToQueryTimeZone() throws Exception {
        JDBCScanner scanner = createScanner("oracle.jdbc.driver.OracleDriver", "Asia/Shanghai", 1);
        setResultColumnClassNames(scanner, List.of("oracle.sql.TIMESTAMPTZ"));

        Assertions.assertTrue(invokeShouldConvertOracleTemporalStringColumn(scanner, 0));
        String converted = invokeConvertOracleTemporalValueToString(scanner, 0,
                "2026-03-12 09:30:15.123456 +00:00");
        Assertions.assertEquals("2026-03-12 17:30:15.123456", converted);
    }

    @Test
    public void testOracleTimestampWithoutTimezoneIsNotNormalized() throws Exception {
        JDBCScanner scanner = createScanner("oracle.jdbc.driver.OracleDriver", "Asia/Shanghai", 1);
        setResultColumnClassNames(scanner, List.of("oracle.sql.TIMESTAMP"));

        Assertions.assertTrue(invokeShouldConvertOracleTemporalStringColumn(scanner, 0));
        String source = "2026-03-12 09:30:15.123456";
        String converted = invokeConvertOracleTemporalValueToString(scanner, 0, source);
        Assertions.assertEquals(source, converted);
    }

    @Test
    public void testOracleTemporalConversionDisabledWithoutQueryTimeZone() throws Exception {
        JDBCScanner scanner = createScanner("oracle.jdbc.driver.OracleDriver", null, 1);
        setResultColumnClassNames(scanner, List.of("oracle.sql.TIMESTAMPTZ"));

        Assertions.assertFalse(invokeShouldConvertOracleTemporalStringColumn(scanner, 0));
    }

    @Test
    public void testOracleQueryTimeZoneShortIdAndInvalidValue() {
        JDBCScanner shortIdScanner = createScanner("oracle.jdbc.driver.OracleDriver", "EST", 1);
        Assertions.assertNotNull(shortIdScanner);

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> createScanner("oracle.jdbc.driver.OracleDriver", "Invalid/QueryTZ", 1));
        Assertions.assertTrue(ex.getMessage().contains("invalid query time zone"));
    }

    @Test
    public void testResolveOracleSessionTimeZoneBranches() throws Exception {
        JDBCScanner scanner = createScanner("oracle.jdbc.driver.OracleDriver", "Asia/Shanghai", 1);

        Assertions.assertNull(invokeResolveOracleSessionTimeZone(scanner, null));
        Assertions.assertNull(invokeResolveOracleSessionTimeZone(scanner, ""));
        Assertions.assertEquals(ZoneOffset.of("+08:00"), invokeResolveOracleSessionTimeZone(scanner, "+08:00"));
        Assertions.assertEquals(ZoneOffset.of("+09:30"), invokeResolveOracleSessionTimeZone(scanner, "09:30"));
        Assertions.assertEquals(ZoneId.of("Asia/Shanghai"), invokeResolveOracleSessionTimeZone(scanner, "Asia/Shanghai"));
        Assertions.assertNotNull(invokeResolveOracleSessionTimeZone(scanner, "EST"));

        InvocationTargetException ex = Assertions.assertThrows(InvocationTargetException.class,
                () -> invokeResolveOracleSessionTimeZoneRaw(scanner, "Invalid/SessionTZ"));
        Assertions.assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    @Test
    public void testInitOracleSessionTimeZoneIfNeededNoopForNonOracle() throws Exception {
        JDBCScanner scanner = createScanner("com.mysql.jdbc.Driver", "Asia/Shanghai", 1);
        Connection connection = proxy(Connection.class, (method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                throw new AssertionError("prepareStatement should not be called for non-oracle scanner");
            }
            return defaultValue(method);
        });
        setField(scanner, "connection", connection);

        invokeInitOracleSessionTimeZoneIfNeeded(scanner);
        Assertions.assertNull(getField(scanner, "oracleSessionTimeZone"));
    }

    @Test
    public void testInitOracleSessionTimeZoneIfNeededLoadsFromDual() throws Exception {
        JDBCScanner scanner = createScanner("oracle.jdbc.driver.OracleDriver", "Asia/Shanghai", 1);

        ResultSet rs = proxy(ResultSet.class, (method, args) -> {
            if ("next".equals(method.getName())) {
                return true;
            }
            if ("getString".equals(method.getName())) {
                return "+08:00";
            }
            return defaultValue(method);
        });

        PreparedStatement ps = proxy(PreparedStatement.class, (method, args) -> {
            if ("executeQuery".equals(method.getName())) {
                return rs;
            }
            return defaultValue(method);
        });

        Connection connection = proxy(Connection.class, (method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                return ps;
            }
            return defaultValue(method);
        });

        setField(scanner, "connection", connection);
        invokeInitOracleSessionTimeZoneIfNeeded(scanner);
        Assertions.assertEquals(ZoneOffset.of("+08:00"), getField(scanner, "oracleSessionTimeZone"));
    }

    @Test
    public void testConvertOracleTimestampLtzHandlesNullAndConvertsValue() throws Exception {
        JDBCScanner scanner = createScanner("oracle.jdbc.driver.OracleDriver", "Asia/Shanghai", 1);
        setResultColumnClassNames(scanner, List.of("oracle.sql.TIMESTAMPLTZ"));

        ResultSet nullTsRs = proxy(ResultSet.class, (method, args) -> {
            if ("getTimestamp".equals(method.getName())) {
                return null;
            }
            return defaultValue(method);
        });
        setField(scanner, "resultSet", nullTsRs);
        Assertions.assertNull(invokeConvertOracleTemporalValueToString(scanner, 0, new Object()));

        ResultSet valueTsRs = proxy(ResultSet.class, (method, args) -> {
            if ("getTimestamp".equals(method.getName())) {
                return Timestamp.valueOf("2026-03-12 09:30:15");
            }
            return defaultValue(method);
        });
        setField(scanner, "resultSet", valueTsRs);
        setField(scanner, "oracleSessionTimeZone", ZoneOffset.of("+00:00"));
        String converted = invokeConvertOracleTemporalValueToString(scanner, 0, new Object());
        Assertions.assertEquals("2026-03-12 17:30:15.000000", converted);
    }

    @Test
    public void testConvertOracleTemporalValueUsesResultSetGetStringForNonStringObject() throws Exception {
        JDBCScanner scanner = createScanner("oracle.jdbc.driver.OracleDriver", "Asia/Shanghai", 1);
        setResultColumnClassNames(scanner, List.of("oracle.sql.TIMESTAMPTZ"));

        ResultSet rs = proxy(ResultSet.class, (method, args) -> {
            if ("getString".equals(method.getName())) {
                return "2026-03-12 09:30:15.123456 +00:00";
            }
            return defaultValue(method);
        });
        setField(scanner, "resultSet", rs);

        String converted = invokeConvertOracleTemporalValueToString(scanner, 0, new Object());
        Assertions.assertEquals("2026-03-12 17:30:15.123456", converted);
    }

    @Test
    public void testNormalizeTimestampStringWithZoneFormatAndFallback() throws Exception {
        JDBCScanner scanner = createScanner("oracle.jdbc.driver.OracleDriver", "Asia/Shanghai", 1);
        String zoneFormatValue = "2026-03-12 09:30:15.123456 UTC";
        String converted = invokeNormalizeTimestampStringToQueryTimeZone(scanner, zoneFormatValue);
        Assertions.assertEquals("2026-03-12 17:30:15.123456", converted);

        String fallback = invokeNormalizeTimestampStringToQueryTimeZone(scanner, "not-a-timestamp");
        Assertions.assertEquals("not-a-timestamp", fallback);
    }

    @Test
    public void testNormalizeTimestampStringReturnsOriginalWhenQueryTimeZoneMissing() throws Exception {
        JDBCScanner scanner = createScanner("oracle.jdbc.driver.OracleDriver", null, 1);
        String value = invokeNormalizeTimestampStringToQueryTimeZone(scanner, "abc");
        Assertions.assertEquals("abc", value);
    }

    @Test
    public void testGetNextChunkStringColumnPaths() throws Exception {
        JDBCScanner scanner = createScanner("oracle.jdbc.driver.OracleDriver", "Asia/Shanghai", 1);
        setField(scanner, "resultSetMetaData", singleColumnMetaData());
        setField(scanner, "resultChunk", singleStringColumnChunk());
        setResultColumnClassNames(scanner, List.of("java.lang.String"));

        ResultSet directStringRs = proxy(ResultSet.class, (method, args) -> {
            if ("getObject".equals(method.getName())) {
                return "plain-text";
            }
            if ("getString".equals(method.getName())) {
                return "fallback";
            }
            return defaultValue(method);
        });
        setField(scanner, "resultSet", directStringRs);
        List<Object[]> chunk = scanner.getNextChunk();
        Assertions.assertEquals("plain-text", ((String[]) chunk.get(0))[0]);

        setField(scanner, "resultChunk", singleStringColumnChunk());
        ResultSet fallbackStringRs = proxy(ResultSet.class, (method, args) -> {
            if ("getObject".equals(method.getName())) {
                return 123;
            }
            if ("getString".equals(method.getName())) {
                return "fallback-value";
            }
            return defaultValue(method);
        });
        setField(scanner, "resultSet", fallbackStringRs);
        chunk = scanner.getNextChunk();
        Assertions.assertEquals("fallback-value", ((String[]) chunk.get(0))[0]);

        setField(scanner, "resultChunk", singleStringColumnChunk());
        setResultColumnClassNames(scanner, List.of("oracle.sql.TIMESTAMPTZ"));
        ResultSet temporalStringRs = proxy(ResultSet.class, (method, args) -> {
            if ("getObject".equals(method.getName())) {
                return "2026-03-12 09:30:15.123456 +00:00";
            }
            if ("getString".equals(method.getName())) {
                return "2026-03-12 09:30:15.123456 +00:00";
            }
            return defaultValue(method);
        });
        setField(scanner, "resultSet", temporalStringRs);
        chunk = scanner.getNextChunk();
        Assertions.assertEquals("2026-03-12 17:30:15.123456", ((String[]) chunk.get(0))[0]);
    }

    @Test
    public void testGetNextChunkConvertsPostgresTimestampWithTimezoneByQueryTimeZone() throws Exception {
        JDBCScanner scanner = createScanner("org.postgresql.Driver", "Asia/Shanghai", 1);
        setField(scanner, "resultSetMetaData", singleColumnMetaData());
        List<Object[]> chunk = new ArrayList<>();
        chunk.add(new Timestamp[1]);
        setField(scanner, "resultChunk", chunk);
        setResultColumnClassNames(scanner, List.of("java.sql.Timestamp"));
        setField(scanner, "postgresTimestampWithTimezoneColumns", List.of(true));

        // Use epoch millis for 2026-03-12 09:30:15 UTC to make test timezone-independent
        long epochMillis = java.time.Instant.parse("2026-03-12T09:30:15Z").toEpochMilli();
        Timestamp sourceTimestamp = new Timestamp(epochMillis);
        ResultSet rs = proxy(ResultSet.class, (method, args) -> {
            if ("getObject".equals(method.getName())) {
                return sourceTimestamp;
            }
            return defaultValue(method);
        });
        setField(scanner, "resultSet", rs);

        chunk = scanner.getNextChunk();
        // 2026-03-12 09:30:15 UTC -> 2026-03-12 17:30:15 Asia/Shanghai (+08:00)
        Assertions.assertEquals(Timestamp.valueOf("2026-03-12 17:30:15"), ((Timestamp[]) chunk.get(0))[0]);
    }

    @Test
    public void testGetNextChunkConvertsPostgresTimeWithTimezoneByQueryTimeZone() throws Exception {
        JDBCScanner scanner = createScanner("org.postgresql.Driver", "Asia/Shanghai", 1);
        setField(scanner, "resultSetMetaData", singleColumnMetaData());
        List<Object[]> chunk = new ArrayList<>();
        chunk.add(new Time[1]);
        setField(scanner, "resultChunk", chunk);
        setResultColumnClassNames(scanner, List.of("java.sql.Time"));
        setField(scanner, "postgresTimeWithTimezoneColumns", List.of(true));

        // Use epoch millis for 06:30:15.789 UTC to test sub-second precision preservation
        long epochMillis = java.time.Instant.parse("1970-01-01T06:30:15.789Z").toEpochMilli();
        Time sourceTime = new Time(epochMillis);
        ResultSet rs = proxy(ResultSet.class, (method, args) -> {
            if ("getObject".equals(method.getName())) {
                return sourceTime;
            }
            return defaultValue(method);
        });
        setField(scanner, "resultSet", rs);

        chunk = scanner.getNextChunk();
        // 06:30:15.789 UTC -> 14:30:15.789 Asia/Shanghai (+08:00)
        Time result = ((Time[]) chunk.get(0))[0];
        long expectedMillis = java.time.Instant.parse("1970-01-01T06:30:15.789Z").toEpochMilli()
                + java.util.TimeZone.getTimeZone("Asia/Shanghai").getOffset(epochMillis)
                - java.util.TimeZone.getDefault().getOffset(epochMillis);
        Assertions.assertEquals(expectedMillis, result.getTime());
    }

    private JDBCScanner createScanner(String driverClassName, String queryTimeZone, int fetchSize) {
        JDBCScanContext scanContext = new JDBCScanContext();
        scanContext.setDriverClassName(driverClassName);
        scanContext.setQueryTimeZone(queryTimeZone);
        scanContext.setStatementFetchSize(fetchSize);
        return new JDBCScanner("unused", scanContext);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> iface, Invocation methodInvocation) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {iface}, (proxy, method, args) -> {
            if ("toString".equals(method.getName())) {
                return iface.getSimpleName() + "Proxy";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return args != null && args.length > 0 && proxy == args[0];
            }
            return methodInvocation.invoke(method, args);
        });
    }

    private Object defaultValue(Method method) {
        Class<?> returnType = method.getReturnType();
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private ResultSetMetaData singleColumnMetaData() {
        return proxy(ResultSetMetaData.class, (method, args) -> {
            if ("getColumnCount".equals(method.getName())) {
                return 1;
            }
            return defaultValue(method);
        });
    }

    private List<Object[]> singleStringColumnChunk() {
        List<Object[]> chunk = new ArrayList<>();
        chunk.add(new String[1]);
        return chunk;
    }

    private void setResultColumnClassNames(JDBCScanner scanner, List<String> classNames) throws Exception {
        setField(scanner, "resultColumnClassNames", classNames);
    }

    private ZoneId invokeResolveOracleSessionTimeZone(JDBCScanner scanner, String value) throws Exception {
        Method method = JDBCScanner.class.getDeclaredMethod("resolveOracleSessionTimeZone", String.class);
        method.setAccessible(true);
        return (ZoneId) method.invoke(scanner, value);
    }

    private void invokeResolveOracleSessionTimeZoneRaw(JDBCScanner scanner, String value) throws Exception {
        Method method = JDBCScanner.class.getDeclaredMethod("resolveOracleSessionTimeZone", String.class);
        method.setAccessible(true);
        method.invoke(scanner, value);
    }

    private void invokeInitOracleSessionTimeZoneIfNeeded(JDBCScanner scanner) throws Exception {
        Method method = JDBCScanner.class.getDeclaredMethod("initOracleSessionTimeZoneIfNeeded");
        method.setAccessible(true);
        method.invoke(scanner);
    }

    private boolean invokeShouldConvertOracleTemporalStringColumn(JDBCScanner scanner, int columnIndex) throws Exception {
        Method method = JDBCScanner.class.getDeclaredMethod("shouldConvertOracleTemporalStringColumn", int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(scanner, columnIndex);
    }

    private boolean invokeIsPostgresTimeWithTimezoneTypeName(JDBCScanner scanner, String typeName)
            throws Exception {
        Method method = JDBCScanner.class.getDeclaredMethod("isPostgresTimeWithTimezoneTypeName", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(scanner, typeName);
    }

    private boolean invokeIsPostgresTimestampWithTimezoneTypeName(JDBCScanner scanner, String typeName)
            throws Exception {
        Method method = JDBCScanner.class.getDeclaredMethod("isPostgresTimestampWithTimezoneTypeName", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(scanner, typeName);
    }

    private String invokeConvertOracleTemporalValueToString(JDBCScanner scanner, int columnIndex, Object resultObject)
            throws Exception {
        Method method = JDBCScanner.class.getDeclaredMethod(
                "convertOracleTemporalValueToString", int.class, Object.class);
        method.setAccessible(true);
        return (String) method.invoke(scanner, columnIndex, resultObject);
    }

    private String invokeNormalizeTimestampStringToQueryTimeZone(JDBCScanner scanner, String value) throws Exception {
        Method method = JDBCScanner.class.getDeclaredMethod("normalizeTimestampStringToQueryTimeZone", String.class);
        method.setAccessible(true);
        return (String) method.invoke(scanner, value);
    }

    private void setField(JDBCScanner scanner, String fieldName, Object value) throws Exception {
        Field field = JDBCScanner.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(scanner, value);
    }

    private Object getField(JDBCScanner scanner, String fieldName) throws Exception {
        Field field = JDBCScanner.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(scanner);
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Method method, Object[] args) throws Exception;
    }
}
