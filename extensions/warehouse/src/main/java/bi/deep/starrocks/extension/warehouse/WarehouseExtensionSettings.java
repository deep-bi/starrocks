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

import com.starrocks.common.Config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Settings of the warehouse extension.
 *
 * <p>They live in fe.conf next to the FE's own settings, but the extension parses the file itself. The FE's
 * {@code Config} class only binds keys that are declared as its fields and silently ignores the rest, and
 * declaring a field would mean changing an FE source file.
 */
public final class WarehouseExtensionSettings {
    /**
     * Allows {@code CREATE WAREHOUSE}. Default {@code false}.
     *
     * <p>{@code false} does not switch existing warehouses off. They keep working and can still be altered and
     * dropped, because an FE that stopped reading them would lose them.
     */
    public static final String KEY_ENABLED = "enable_warehouse_extension";

    /**
     * Skips the check that the jar was built for the running FE version. Default {@code false}.
     */
    public static final String KEY_SKIP_VERSION_CHECK = "warehouse_extension_skip_version_check";

    /**
     * Checks USAGE on a user warehouse for every query and load of a user session. Default {@code true}.
     *
     * <p>The check sits in the path of every request. This switch turns it off without removing the jar, which
     * is not a safe way to disable anything once warehouses exist.
     */
    public static final String KEY_CHECK_USAGE = "warehouse_extension_check_usage";

    private static final WarehouseExtensionSettings DEFAULTS = new WarehouseExtensionSettings(false, false, true);

    private static volatile WarehouseExtensionSettings current = DEFAULTS;

    private final boolean enabled;
    private final boolean skipVersionCheck;
    private final boolean checkUsage;

    private WarehouseExtensionSettings(boolean enabled, boolean skipVersionCheck, boolean checkUsage) {
        this.enabled = enabled;
        this.skipVersionCheck = skipVersionCheck;
        this.checkUsage = checkUsage;
    }

    /**
     * Settings with the USAGE check on, which is its default.
     */
    public static WarehouseExtensionSettings of(boolean enabled, boolean skipVersionCheck) {
        return new WarehouseExtensionSettings(enabled, skipVersionCheck, DEFAULTS.checkUsage);
    }

    public static WarehouseExtensionSettings of(boolean enabled, boolean skipVersionCheck, boolean checkUsage) {
        return new WarehouseExtensionSettings(enabled, skipVersionCheck, checkUsage);
    }

    public static WarehouseExtensionSettings defaults() {
        return DEFAULTS;
    }

    /**
     * The settings the extension loaded at FE start, or the defaults if it has not loaded.
     */
    public static WarehouseExtensionSettings current() {
        return current;
    }

    static void install(WarehouseExtensionSettings settings) {
        current = settings;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isSkipVersionCheck() {
        return skipVersionCheck;
    }

    public boolean isCheckUsage() {
        return checkUsage;
    }

    /**
     * The file the FE itself reads: {@code $STARROCKS_HOME/conf/fe.conf}.
     */
    public static Path defaultFeConfPath() {
        return feConfPath(Config.STARROCKS_HOME_DIR);
    }

    static Path feConfPath(String starrocksHome) {
        if (starrocksHome == null || starrocksHome.isEmpty()) {
            throw new IllegalStateException("STARROCKS_HOME is not set, cannot locate fe.conf");
        }
        return Paths.get(starrocksHome, "conf", "fe.conf");
    }

    public static WarehouseExtensionSettings loadFromFile(Path feConf) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(feConf, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return fromProperties(props);
    }

    public static WarehouseExtensionSettings fromProperties(Properties props) {
        return new WarehouseExtensionSettings(
                parseBoolean(props, KEY_ENABLED, DEFAULTS.enabled),
                parseBoolean(props, KEY_SKIP_VERSION_CHECK, DEFAULTS.skipVersionCheck),
                parseBoolean(props, KEY_CHECK_USAGE, DEFAULTS.checkUsage));
    }

    /**
     * Strict: a typo such as "ture" must stop the FE instead of quietly leaving the setting off.
     */
    private static boolean parseBoolean(Properties props, String key, boolean defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(
                "Invalid value for " + key + " in fe.conf: '" + raw + "'. Expected true or false");
    }

    @Override
    public String toString() {
        return KEY_ENABLED + "=" + enabled + ", " + KEY_SKIP_VERSION_CHECK + "=" + skipVersionCheck
                + ", " + KEY_CHECK_USAGE + "=" + checkUsage;
    }
}
