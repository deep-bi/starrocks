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

package bi.deep.starrocks.extension.warehouse;

import com.starrocks.common.DdlException;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The properties a user can set on a warehouse, parsed and validated from the {@code PROPERTIES (...)} clause of
 * {@code CREATE WAREHOUSE} and from {@code ALTER WAREHOUSE ... SET (...)}.
 *
 * <p>Keys are matched without regard to case. A key the extension does not know is an error, so a typo cannot
 * leave a warehouse with a default the user did not ask for.
 */
final class WarehouseOptions {
    /**
     * How many compute nodes of the warehouse StarMgr assigns each tablet to. The extension passes it to StarMgr
     * unchanged.
     */
    static final String REPLICA_NUMBER = "replica_number";
    static final int DEFAULT_REPLICA_NUMBER = 1;

    private final int replicaNumber;

    private WarehouseOptions(int replicaNumber) {
        this.replicaNumber = replicaNumber;
    }

    /**
     * @param properties the PROPERTIES clause, may be null or empty
     */
    static WarehouseOptions forCreate(Map<String, String> properties) throws DdlException {
        Map<String, String> remaining = normalize(properties);
        int replicaNumber = takeReplicaNumber(remaining, DEFAULT_REPLICA_NUMBER);
        rejectUnknown(remaining);
        return new WarehouseOptions(replicaNumber);
    }

    /**
     * @param properties          the SET clause; it must name at least one property
     * @param currentReplicaNumber the value kept when the clause does not set it
     */
    static WarehouseOptions forAlter(Map<String, String> properties, int currentReplicaNumber) throws DdlException {
        Map<String, String> remaining = normalize(properties);
        if (remaining.isEmpty()) {
            throw new DdlException("ALTER WAREHOUSE needs at least one property. Supported: " + REPLICA_NUMBER);
        }
        int replicaNumber = takeReplicaNumber(remaining, currentReplicaNumber);
        rejectUnknown(remaining);
        return new WarehouseOptions(replicaNumber);
    }

    int replicaNumber() {
        return replicaNumber;
    }

    /**
     * @return the text shown in the Property column of SHOW WAREHOUSES
     */
    static String describe(int replicaNumber) {
        return REPLICA_NUMBER + "=" + replicaNumber;
    }

    private static Map<String, String> normalize(Map<String, String> properties) throws DdlException {
        Map<String, String> normalized = new TreeMap<>();
        if (properties == null) {
            return normalized;
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (normalized.containsKey(key)) {
                throw new DdlException("Warehouse property '" + key + "' is given more than once");
            }
            normalized.put(key, entry.getValue());
        }
        return normalized;
    }

    private static int takeReplicaNumber(Map<String, String> remaining, int fallback) throws DdlException {
        if (!remaining.containsKey(REPLICA_NUMBER)) {
            return fallback;
        }
        String raw = remaining.remove(REPLICA_NUMBER);
        int value;
        try {
            value = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw invalidReplicaNumber(raw);
        }
        if (value < 1) {
            throw invalidReplicaNumber(raw);
        }
        return value;
    }

    private static DdlException invalidReplicaNumber(String raw) {
        return new DdlException("Invalid " + REPLICA_NUMBER + " '" + raw + "': expected a positive integer");
    }

    private static void rejectUnknown(Map<String, String> remaining) throws DdlException {
        if (!remaining.isEmpty()) {
            throw new DdlException("Unknown warehouse property " + remaining.keySet()
                    + ". Supported: " + REPLICA_NUMBER);
        }
    }
}
