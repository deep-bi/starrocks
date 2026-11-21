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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Properties;

public class WarehouseExtensionSettingsTest {

    @AfterEach
    public void after() {
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.defaults());
    }

    private static Properties props(String... keyValues) {
        Properties props = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            props.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return props;
    }

    /**
     * The safe defaults: no new warehouses, the version check on, the USAGE check on.
     */
    @Test
    public void theDefaultsAreTheSafeChoices() {
        WarehouseExtensionSettings settings = WarehouseExtensionSettings.fromProperties(new Properties());

        Assertions.assertFalse(settings.isEnabled());
        Assertions.assertFalse(settings.isSkipVersionCheck());
        Assertions.assertTrue(settings.isCheckUsage());
        Assertions.assertFalse(WarehouseExtensionSettings.defaults().isEnabled());
        Assertions.assertFalse(WarehouseExtensionSettings.defaults().isSkipVersionCheck());
        Assertions.assertTrue(WarehouseExtensionSettings.defaults().isCheckUsage());
    }

    @Test
    public void theUsageCheckCanBeSwitchedOff() {
        WarehouseExtensionSettings off = WarehouseExtensionSettings.fromProperties(
                props(WarehouseExtensionSettings.KEY_CHECK_USAGE, "false"));
        Assertions.assertFalse(off.isCheckUsage());
        Assertions.assertFalse(off.isEnabled());

        Assertions.assertTrue(WarehouseExtensionSettings.of(true, false).isCheckUsage(), "on unless said otherwise");
        Assertions.assertFalse(WarehouseExtensionSettings.of(true, false, false).isCheckUsage());
        Assertions.assertThrows(IllegalArgumentException.class, () -> WarehouseExtensionSettings.fromProperties(
                props(WarehouseExtensionSettings.KEY_CHECK_USAGE, "off")));
    }

    @Test
    public void theKeysAreTheDocumentedOnes() {
        Assertions.assertEquals("enable_warehouse_extension", WarehouseExtensionSettings.KEY_ENABLED);
        Assertions.assertEquals("warehouse_extension_skip_version_check",
                WarehouseExtensionSettings.KEY_SKIP_VERSION_CHECK);
        Assertions.assertEquals("warehouse_extension_check_usage", WarehouseExtensionSettings.KEY_CHECK_USAGE);
    }

    @Test
    public void valuesAreTrimmedAndCaseInsensitive() {
        WarehouseExtensionSettings settings = WarehouseExtensionSettings.fromProperties(props(
                WarehouseExtensionSettings.KEY_ENABLED, " TRUE ",
                WarehouseExtensionSettings.KEY_SKIP_VERSION_CHECK, "True"));
        Assertions.assertTrue(settings.isEnabled());
        Assertions.assertTrue(settings.isSkipVersionCheck());

        settings = WarehouseExtensionSettings.fromProperties(props(
                WarehouseExtensionSettings.KEY_ENABLED, "FALSE",
                WarehouseExtensionSettings.KEY_SKIP_VERSION_CHECK, " false"));
        Assertions.assertFalse(settings.isEnabled());
        Assertions.assertFalse(settings.isSkipVersionCheck());

        // An empty value means "not set".
        settings = WarehouseExtensionSettings.fromProperties(props(WarehouseExtensionSettings.KEY_ENABLED, "  "));
        Assertions.assertFalse(settings.isEnabled());
    }

    /**
     * A typo must stop the FE. Reading "ture" as false would leave the setting off without anyone noticing.
     */
    @Test
    public void aTypoIsAnError() {
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class,
                () -> WarehouseExtensionSettings.fromProperties(props(WarehouseExtensionSettings.KEY_ENABLED, "ture")));
        Assertions.assertTrue(e.getMessage().contains(WarehouseExtensionSettings.KEY_ENABLED), e.getMessage());
        Assertions.assertTrue(e.getMessage().contains("'ture'"), e.getMessage());

        Assertions.assertThrows(IllegalArgumentException.class, () -> WarehouseExtensionSettings.fromProperties(
                props(WarehouseExtensionSettings.KEY_SKIP_VERSION_CHECK, "yes")));
    }

    @Test
    public void settingsAreReadFromAnFeConfFile(@TempDir Path dir) throws Exception {
        Path feConf = dir.resolve("fe.conf");
        Files.write(feConf, Arrays.asList(
                "# the FE's own settings are ignored here",
                "http_port = 8030",
                "enable_warehouse_extension = true",
                "JAVA_OPTS=\"-Xmx8g\""), StandardCharsets.UTF_8);

        WarehouseExtensionSettings settings = WarehouseExtensionSettings.loadFromFile(feConf);

        Assertions.assertTrue(settings.isEnabled());
        Assertions.assertFalse(settings.isSkipVersionCheck());
        Assertions.assertThrows(IOException.class,
                () -> WarehouseExtensionSettings.loadFromFile(dir.resolve("missing.conf")));
    }

    @Test
    public void theCurrentSettingsAreTheInstalledOnes() {
        Assertions.assertSame(WarehouseExtensionSettings.defaults(), WarehouseExtensionSettings.current());

        WarehouseExtensionSettings on = WarehouseExtensionSettings.of(true, true);
        WarehouseExtensionSettings.install(on);

        Assertions.assertSame(on, WarehouseExtensionSettings.current());
        Assertions.assertEquals("enable_warehouse_extension=true, warehouse_extension_skip_version_check=true,"
                + " warehouse_extension_check_usage=true", on.toString());
    }

    @Test
    public void feConfIsLookedUpUnderStarrocksHome() {
        Assertions.assertEquals(Paths.get("/opt/starrocks/fe", "conf", "fe.conf"),
                WarehouseExtensionSettings.feConfPath("/opt/starrocks/fe"));
        Assertions.assertThrows(IllegalStateException.class, () -> WarehouseExtensionSettings.feConfPath(null));
        Assertions.assertThrows(IllegalStateException.class, () -> WarehouseExtensionSettings.feConfPath(""));

        // The build runs the tests with STARROCKS_HOME set, as a real FE has it.
        Assertions.assertNotNull(Config.STARROCKS_HOME_DIR, "the test task must set STARROCKS_HOME");
        Assertions.assertEquals(Paths.get(Config.STARROCKS_HOME_DIR, "conf", "fe.conf"),
                WarehouseExtensionSettings.defaultFeConfPath());
    }
}
